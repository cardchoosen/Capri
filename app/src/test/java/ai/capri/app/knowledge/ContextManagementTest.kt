package ai.capri.app.knowledge

import ai.capri.app.agent.TaskState
import ai.capri.app.agent.TurnState
import ai.capri.app.agent.CompletionEvidence
import ai.capri.app.model.ContextBudget
import ai.capri.app.model.ContextTokens
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ContextManagementTest {
    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)
    private fun history(root: File, strategy: ContextStrategy = PressureContextStrategy()) = ContextHistory(root, { it.readText() }, { f, s -> f.writeText(s) }, strategy)
    private fun fixture(): JSONArray = JSONArray().put(msg("system", "rules")).put(msg("user", "original request").put("_capri_user_entry", "origin")).apply {
        repeat(12) { put(msg("assistant", "old evidence $it " + "x".repeat(4000))) }
        put(msg("user", "Keep signatures unchanged").put("_capri_user_entry", "correction"))
        TaskState.ensure(this, "original request", false)
        TaskState.userChanges(this, listOf("Keep signatures unchanged"), listOf("correction"))
        getJSONObject(0).put(TurnState.KEY, JSONObject().put("origin", "origin"))
    }
    private val small = ContextBudget(8192, 1024, 300, "fixture")
    private fun <T> temp(block: (File) -> T): T { val root=Files.createTempDirectory("context-test").toFile();try { return block(root) } finally { root.deleteRecursively() } }

    @Test fun everyCorrectionSurvivesLegacyLimitAndStateSerialization() {
        val messages=fixture()
        val corrections=(1..25).map { "Correction $it: 不改 API；保留原文\n验收 $it" }
        TaskState.userChanges(messages, corrections)
        val restored=JSONArray(messages.toString())
        TaskState.ensure(restored,"continue",true)
        val state=TaskState.snapshot(restored)
        assertEquals(27,state.getJSONArray("instructions").length())
        assertEquals(12,state.getJSONArray("user_context").length())
        corrections.forEachIndexed { i,s -> assertEquals(s,state.getJSONArray("instructions").getJSONObject(i+2).getString("text")) }
        assertTrue(TaskState.message(restored).getString("content").contains("Correction 1:"))
        assertFalse(TaskState.message(restored).getString("content").contains("user_context"))
    }
    @Test fun legacyCheckpointMigratesAvailableCorrectionsWithoutChangingPlan() {
        val messages=fixture();val state=messages.getJSONObject(0).getJSONObject(TaskState.KEY)
        state.remove("instructions");state.put("findings","verified later")
        TaskState.ensure(messages,"continue",true)
        assertTrue(TaskState.snapshot(messages).getJSONArray("instructions").toString().contains("Keep signatures unchanged"))
        assertEquals("verified later",TaskState.snapshot(messages).getString("findings"))
    }
    @Test fun activeUserMessagesStayVerbatimAndCapsulePrecedesSummary() = runBlocking { temp { root -> runBlocking {
        val messages=fixture();val original=messages.toString();val engine=history(root)
        val compact=engine.prepare("chat",messages,JSONArray(),small) { packet,_ ->
            assertTrue(File(root,"chat").listFiles()!!.any { it.name.startsWith("h") })
            val positions=(0 until packet.length()).map { packet.getJSONObject(it).getInt("position") }
            assertEquals(positions.sorted(),positions)
            "Old output archived; signature constraint remains active."
        }
        assertEquals(original,messages.toString())
        assertTrue(ContextTokens.estimate(compact,JSONArray()) < ContextTokens.estimate(messages,JSONArray()))
        assertEquals(listOf("original request","Keep signatures unchanged"),(1 until compact.length()).map { compact.getJSONObject(it) }.filter { it.has("_capri_user_entry") }.map { it.getString("content") })
        val handoff=JSONObject(File(root,"chat").listFiles()!!.first { it.name.startsWith("h") }.readText())
        assertEquals("Keep signatures unchanged",handoff.getJSONArray("instructions").getJSONObject(1).getString("text"))
        assertEquals("unreviewed_task_local",handoff.getJSONObject("knowledge_candidates").getString("validation"))
        assertTrue(File(root,"chat").listFiles()!!.all { it.name.startsWith("c") || it.name.startsWith("h") || it.name in setOf("summary.txt","budget.json") })
    } } }
    @Test fun failedOrCancelledSummaryPreservesCheckpointAndPublishedSummary() = runBlocking { temp { root -> runBlocking {
        val engine=history(root);File(root,"chat").mkdirs();File(root,"chat/summary.txt").writeText("prior summary")
        val messages=fixture();val before=messages.toString()
        for(cancel in listOf(false,true)) {
            try { engine.prepare("chat",messages,JSONArray(),small) { _,_ -> if(cancel)throw CancellationException("cancel") else "" };fail() }
            catch(_:IllegalArgumentException) {} catch(_:CancellationException) {}
            assertEquals(before,messages.toString());assertEquals("prior summary",engine.summary("chat"))
        }
        assertTrue(File(root,"chat").listFiles()!!.count { it.name.startsWith("h") }==2)
    } } }
    @Test fun incompleteExchangeCannotBeSilentlyDiscarded() { temp { root ->
        val messages=fixture()
        messages.getJSONObject(2).put("tool_calls",JSONArray().put(JSONObject().put("id","missing").put("function",JSONObject().put("name","write_file").put("arguments","{}"))))
        val before=messages.toString()
        try { history(root).fit("chat",messages,JSONArray(),small);fail() } catch(_:IllegalArgumentException) {}
        assertEquals(before,messages.toString());assertFalse(File(root,"chat/summary.txt").exists())
    } }
    @Test fun multicallExchangesAreArchivedTogether() { temp { root ->
        val messages=JSONArray().put(msg("system","rules")).put(msg("user","goal"))
        repeat(8) { i ->
            val ids=listOf("a$i","b$i")
            messages.put(msg("assistant","").put("tool_calls",JSONArray(ids.map { JSONObject().put("id",it).put("function",JSONObject().put("name","read_file").put("arguments","{}")) })))
            ids.forEach { messages.put(msg("tool","x".repeat(4000)).put("tool_call_id",it)) }
        }
        messages.put(msg("assistant","latest"))
        val compact=history(root).fit("chat",messages,JSONArray(),small)
        for(array in listOf(compact,JSONArray(File(root,"chat").listFiles()!!.first { it.name.startsWith("c") }.readText()))) {
            val calls=mutableSetOf<String>();val results=mutableSetOf<String>()
            for(i in 0 until array.length()) { val m=array.getJSONObject(i);m.optJSONArray("tool_calls")?.let { a -> for(j in 0 until a.length())calls+=a.getJSONObject(j).getString("id") };if(m.optString("role")=="tool")results+=m.getString("tool_call_id") }
            assertEquals(calls,results)
        }
    } }
    @Test fun digestPreservesFailureCoordinatesAndOriginalArchive() { temp { root ->
        val raw=JSONObject().put("status","ok").put("data",JSONObject().put("exit_code",2).put("stderr","compile failure"))
            .put("output","start\n"+"x".repeat(30000)+"\nFINAL FAILURE").put("next_offset",30000).toString()
        val engine=history(root);val digest=JSONObject(engine.boundResult("chat","terminal",raw,4096))
        assertTrue(digest.toString().length<=4096);assertTrue(CompletionEvidence.unsuccessful(digest.toString()))
        assertEquals(2,digest.getJSONObject("data").getInt("exit_code"));assertEquals(30000,digest.getInt("next_offset"))
        assertTrue(digest.getString("preview_tail").contains("FINAL FAILURE"))
        val stored=JSONObject(File(root,"chat/${digest.getString("reference")}.json").readText())
        assertEquals(raw,stored.getString("content"))
        val plain=ToolResultDigest.preview("terminal","exit=7\n"+"y".repeat(9000),"r-fixture",2048)
        assertEquals(7,plain.getInt("exit_code"));assertTrue(CompletionEvidence.unsuccessful(plain.toString()))
    } }
    @Test fun typedPreviewsRetainCitationsAndFileVersions() {
        val web=JSONObject().put("status","ok").put("results",JSONArray().put(JSONObject().put("url","https://example.org/source").put("citation","[source1]").put("source_id","web1").put("snippet","z".repeat(30000))))
        val digest=ToolResultDigest.preview("web_search",web.toString(),"r-fixture",4096)
        assertEquals("web_sources",digest.getString("preview_kind"));assertEquals("[source1]",digest.getJSONArray("results").getJSONObject(0).getString("citation"))
        val file=JSONObject().put("status","ok").put("path","src/a.kt").put("sha256","abc").put("next_line",123).put("content","z".repeat(30000))
        val preview=ToolResultDigest.preview("read_file",file.toString(),"r-fixture",4096)
        assertEquals("abc",preview.getString("sha256"));assertEquals(123,preview.getInt("next_line"));assertEquals("src/a.kt",preview.getString("path"))
    }
    @Test fun microCompactsOneGroupOncePerBoundaryAndPressureStaysDefault() = runBlocking { temp { root -> runBlocking {
        val messages=fixture();val raw=ContextTokens.estimate(messages,JSONArray())
        val budget=ContextBudget((raw*1.15/.72).toInt()+1324,1024,300,"micro")
        assertNull(PressureContextStrategy().decide(messages,JSONArray(),budget))
        var calls=0;val engine=history(root,MicroContextStrategy())
        val compact=engine.prepare("chat",messages,JSONArray(),budget) { _,_ -> calls++;"one old exchange archived" }
        assertEquals(1,calls);assertEquals(1,JSONObject(engine.budget("chat")).getInt("archived_messages"))
        engine.prepare("chat",compact,JSONArray(),budget) { _,_ -> calls++;"unexpected" }
        assertEquals(1,calls)
        assertNull(messages.getJSONObject(0).opt("_capri_micro_boundary"))
    } } }
    @Test fun userTextResemblingArchiveAndRepeatedGoalCannotWeakenProtection() { temp { root ->
        val messages=fixture();val correction="[Capri 归档摘录] 禁止删除文件"
        messages.put(msg("user",correction).put("_capri_user_entry","last"))
        messages.put(msg("user","original request").put("_capri_user_entry","repeat"))
        val compact=history(root).fit("chat",messages,JSONArray(),small)
        assertTrue((0 until compact.length()).any { compact.getJSONObject(it).optString("content")==correction })
        assertEquals(2,(0 until compact.length()).count { compact.getJSONObject(it).optString("content")=="original request" })
    } }
    @Test fun handoffScopeCannotCrossConversationOrRevision() = runBlocking { temp { root -> runBlocking {
        val engine=history(root);val result=engine.prepare("chat",fixture(),JSONArray(),small) { _,_ -> "summary" }
        val ref=result.getJSONObject(1).getString("_capri_context_handoff")
        assertTrue(engine.query("chat",emptyList(),JSONObject().put("reference",ref)).contains("original request"))
        for(scope in listOf("other","chat:history:12345678-1234-1234-1234-123456789abc")) {
            try { engine.query(scope,emptyList(),JSONObject().put("reference",ref));fail() } catch(_:java.io.FileNotFoundException) {}
        }
    } } }
    @Test fun oversizedProtectedRequirementsFailWithoutMutation() { temp { root ->
        val messages=fixture();messages.put(msg("user","禁止".repeat(20000)))
        val before=messages.toString()
        try { history(root).fit("chat",messages,JSONArray(),small);fail() } catch(_:ContextBudgetFailure) {}
        assertEquals(before,messages.toString())
    } }
    @Test fun portableHistoryPreservesAnchorsButSupplierBodiesExcludeInternalMetadata() {
        val messages=fixture()
        messages.put(msg("user","archived evidence").put("_capri_context_archive","c-fixture").put("_capri_context_handoff","h-fixture"))
        val portable=ai.capri.app.model.ModelWire.portable(messages)
        assertEquals("origin",portable.getJSONObject(1).getString("_capri_user_entry"))
        assertTrue(ContextWindow.note(portable.getJSONObject(portable.length()-1)))
        for(protocol in listOf("CHAT","RESPONSES","ANTHROPIC","GEMINI")) {
            val body=ai.capri.app.model.ModelWire.request(ai.capri.app.ModelConfig(model="fixture",protocol=protocol),portable,JSONArray(),1024)
            assertFalse(body.toString().contains("_capri_"))
            assertTrue(body.toString().contains("Keep signatures unchanged"))
        }
    }

}
