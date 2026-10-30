package ai.capri.app

import ai.capri.app.agent.TaskState
import ai.capri.app.agent.TurnState
import ai.capri.app.agent.CompletionEvidence
import ai.capri.app.knowledge.ContextHistory
import ai.capri.app.model.ContextBudget
import ai.capri.app.model.ContextTokens
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Production AtomicFile and recovery paths, isolated fixture data and scripted summary; no provider calls. */
@RunWith(AndroidJUnit4::class)
class ContextManagementDeviceTest {
    @Test fun compactionRecoveryAndFullResultRemainScopedAndReadable() = runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"context-management-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            fun msg(role:String,text:String)=JSONObject().put("role",role).put("content",text)
            val messages=JSONArray().put(msg("system","rules")).put(msg("user","Fix the build").put("_capri_user_entry","origin"))
            repeat(15) { messages.put(msg("assistant","Old analysis $it\n"+"x".repeat(4000))) }
            val corrections=(1..20).map { "Correction $it: Preserve API $it unchanged" }
            corrections.forEachIndexed { i,text -> messages.put(msg("user",text).put("_capri_user_entry","change-$i")) }
            TaskState.ensure(messages,"Fix the build",false);TaskState.userChanges(messages,corrections)
            messages.getJSONObject(0).put(TurnState.KEY,JSONObject().put("origin","origin"))
            val history=ContextHistory(File(root,"history"));val recovery=AgentRecovery(File(root,"recovery"))
            recovery.save("fixture",messages)
            val original=messages.toString();val budget=ContextBudget(8192,1024,300,"fixture")
            val raw=ContextTokens.estimate(messages,JSONArray());var calls=0
            val start=android.os.SystemClock.elapsedRealtime()
            val compact=history.prepare("fixture",messages,JSONArray(),budget) { _,_ -> calls++;"Older analyses archived. All twenty corrections stay active." }
            val elapsed=android.os.SystemClock.elapsedRealtime()-start
            assertEquals(1,calls);assertEquals(original,messages.toString())
            assertTrue(budget.fits(ContextTokens.estimate(compact,JSONArray())))
            recovery.save("fixture",compact)
            val restored=requireNotNull(AgentRecovery(File(root,"recovery")).load("fixture"))
            assertEquals(21,TaskState.snapshot(restored).getJSONArray("instructions").length())
            corrections.forEach { text -> assertTrue((0 until restored.length()).any { restored.getJSONObject(it).optString("content")==text }) }
            val handoffRef=restored.getJSONObject(1).getString("_capri_context_handoff")
            val reloaded=ContextHistory(File(root,"history"))
            assertTrue(reloaded.query("fixture",emptyList(),JSONObject().put("reference",handoffRef)).contains("unreviewed_task_local"))
            try { reloaded.query("other",emptyList(),JSONObject().put("reference",handoffRef));fail() } catch(_:java.io.FileNotFoundException) {}
            val full=JSONObject().put("status","ok").put("data",JSONObject().put("exit_code",7)).put("output","x".repeat(30000)+"end of failure").toString()
            val digest=JSONObject(history.boundResult("fixture","terminal",full,4096))
            assertTrue(CompletionEvidence.unsuccessful(digest.toString()))
            val ref=digest.getString("reference")
            val page=JSONObject(reloaded.query("fixture",emptyList(),JSONObject().put("reference",ref).put("offset",28000)))
            assertTrue(page.getString("text").contains("end of failure"))
            val prior=history.summary("fixture")
            try { history.prepare("fixture",messages,JSONArray(),budget) { _,_ -> "" };fail() } catch(_:IllegalArgumentException) {}
            assertEquals(prior,history.summary("fixture"))
            assertEquals(restored.toString(),requireNotNull(recovery.load("fixture")).toString())
            android.util.Log.i("ContextManagementFixture",JSONObject().put("summary_calls",calls).put("raw_estimate_before",raw)
                .put("raw_estimate_after",ContextTokens.estimate(compact,JSONArray())).put("local_ms",elapsed).put("provider_calls",0).toString())
        } finally { root.deleteRecursively() }
    }
}
