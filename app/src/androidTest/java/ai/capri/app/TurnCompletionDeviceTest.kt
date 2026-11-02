package ai.capri.app

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Runs the real Agent loop against a private HTTPS fixture; no real account or remote operation. */
class TurnCompletionDeviceTest {
    private fun reply(text: String, calls: JSONArray? = null): MockResponse {
        val message=JSONObject().put("role","assistant").put("content",text)
        calls?.let{message.put("tool_calls",it)}
        return MockResponse().setHeader("Content-Type","application/json").setBody(JSONObject()
            .put("choices",JSONArray().put(JSONObject().put("message",message).put("finish_reason",if(calls==null)"stop" else "tool_calls"))).toString())
    }
    private suspend fun respond(vm: CapriModel, chat: Session, config: ModelConfig): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        CapriModel::class.java.getDeclaredMethod("respond", Session::class.java, ModelConfig::class.java, kotlin.coroutines.Continuation::class.java)
            .apply{isAccessible=true}.invoke(vm,chat,config,continuation)
    }
    @Test fun preamblesContinueWithoutReplayingToolsAndExhaustionKeepsCheckpoint() {
        val ins=InstrumentationRegistry.getInstrumentation();val app=ins.targetContext
        val certificate=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val responses=java.util.concurrent.LinkedBlockingQueue<MockResponse>()
        val executionRequests=java.util.concurrent.atomic.AtomicInteger()
        val server=MockWebServer().apply{
            useHttps(serverTls.sslSocketFactory(),false)
            dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                val body=JSONObject(request.body.clone().readUtf8())
                // Independent final-voice rendering has no tools; keep it separate from execution fixtures.
                return if(!body.has("tools"))reply("Completed.") else { executionRequests.incrementAndGet(); responses.poll(5,TimeUnit.SECONDS)?:MockResponse().setResponseCode(500) }
            }}
            start()
        }
        val client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager)
            .callTimeout(15,TimeUnit.SECONDS).build()
        val scenario=ActivityScenario.launch(MainActivity::class.java);lateinit var vm:CapriModel
        scenario.onActivity{vm=AgentRuntime.get(it.application)}
        val http=CapriModel::class.java.getDeclaredField("http").apply{isAccessible=true};val original=http.get(vm)
        val chats=mutableListOf<Session>()
        val config=ModelConfig(endpoint=server.url("/").toString(),model="completion-fixture",key="fixture",contextWindow=131072,maxOutput=4096,contextSource="manual")
        fun chat(request:String)=Session(title="Completion fixture",messages=mutableListOf(Entry("user",request))).also { c ->
            ins.runOnMainSync{vm.sessions.add(c)};chats+=c
        }
        fun take():JSONObject { repeat(4){val body=JSONObject(requireNotNull(server.takeRequest(5,TimeUnit.SECONDS)).body.readUtf8());if(body.has("tools"))return body};error("Execution request missing") }
        fun execute(c:Session)=runBlocking(Dispatchers.Main){respond(vm,c,config)}
        try {
            assumeTrue("Wait for active user tasks before isolated loop verification",vm.jobs.isEmpty())
            http.set(vm,client)
            val success=chat("查看 note.txt 并报告内容")
            vm.store.workspace.file(success.id,"note.txt").writeText("fixture evidence")
            responses.add(reply("先查看 note.txt 的内容。"))
            val call=JSONObject().put("id","fixture-read").put("type","function").put("function",JSONObject().put("name","read_file").put("arguments","{\"path\":\"note.txt\"}"))
            responses.add(reply("",JSONArray().put(call)))
            responses.add(reply("我先检查文件。"))
            responses.add(reply("note.txt 的内容是 fixture evidence，读取完成。"))
            execute(success)
            val requests=(1..4).map{take()}
            assertEquals(1,success.messages.count{it.role=="tool"})
            assertTrue(success.messages.single{it.role=="tool"}.text.contains("fixture evidence"))
            assertEquals(1,success.messages.count{it.finalReply})
            assertTrue(success.messages.last().finalReply)
            assertFalse(success.messages.any{it.role=="status"})
            val finalHistory=requests.last().getJSONArray("messages")
            assertEquals(1,(0 until finalHistory.length()).count{finalHistory.getJSONObject(it).optString("role")=="tool"})
            assertEquals(TurnCompletion.guidance,finalHistory.getJSONObject(finalHistory.length()-1).getString("content"))
            val recovery=AgentRecovery(app.filesDir.resolve("agent-recovery"));assertNull(recovery.load(success.id))
            val stuck=chat("查看 note.txt")
            repeat(3){responses.add(reply("先查看 note.txt 的内容。"))}
            execute(stuck);repeat(3){take()}
            assertEquals(0,stuck.messages.count{it.finalReply})
            assertTrue(stuck.messages.last().text.contains("任务尚未完成"))
            assertNotNull(recovery.load(stuck.id))
            val trace=vm.store.workspace.folder(stuck.id,"metrics").listFiles()!!.single().readText()
            assertTrue(trace.contains("incomplete_preamble"));assertTrue(trace.contains("\"status\":\"incomplete\""))
            assertFalse(app.filesDir.resolve("context-history/${stuck.id}/last-turn.txt").exists())
            for((request,text)in listOf("先不执行，给我方案" to "先查看文件，再修改。","查看文件" to "需要你确认目标文件路径，才能继续。")) {
                val c=chat(request);responses.add(reply(text));execute(c);take()
                assertTrue(c.messages.last().finalReply);assertNull(recovery.load(c.id))
            }
            val explicit=chat("@skill-creator Explain the instruction language requirement without creating a Skill.")
            responses.add(reply("New Skill instructions must be English."));execute(explicit)
            val firstSkillRequest=take().toString();assertTrue(firstSkillRequest.contains("Author new and updated Capri Skills entirely in English"))
            assertEquals(1,explicit.messages.count{it.role=="skill"});assertEquals(0,explicit.messages.count{it.role=="tool"})
            ins.runOnMainSync{explicit.messages.add(Entry("user","And if the user speaks Chinese?"))}
            responses.add(reply("The Skill remains English; the conversation follows the user's language."));execute(explicit)
            assertTrue(take().toString().contains("Author new and updated Capri Skills entirely in English"))
            assertEquals(1,explicit.messages.count{it.role=="skill"})
            val viaTool=chat("Read the relevant Capri diagnostic Skill.")
            val skillCall=JSONObject().put("id","skill-read").put("type","function").put("function",JSONObject().put("name","skill").put("arguments","{\"id\":\"capri-diagnostics\"}"))
            responses.add(reply("",JSONArray().put(skillCall)));responses.add(reply("The diagnostic guidance is loaded."));execute(viaTool);take()
            assertTrue(take().toString().contains("A missing tool call is not a tool failure"))
            assertEquals(1,viaTool.messages.count{it.role=="skill"});assertEquals(1,viaTool.messages.count{it.role=="tool"})
            ins.runOnMainSync{viaTool.messages.add(Entry("user","What should we distinguish first?"))}
            responses.add(reply("Distinguish model, tool, runtime and UI failures."));execute(viaTool)
            assertTrue(take().toString().contains("A missing tool call is not a tool failure"))
            assertEquals(14,executionRequests.get())
        } finally {
            http.set(vm,original)
            ins.runOnMainSync{vm.sessions.removeAll{c->chats.any{it.id==c.id}};vm.persist()}
            chats.forEach{SessionDeletion(app.filesDir).cleanup(it.id)}
            server.shutdown();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll();scenario.close()
        }
    }
}
