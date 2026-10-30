package ai.capri.app

import ai.capri.app.agent.TaskState
import ai.capri.app.agent.TurnState
import ai.capri.app.model.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Local HTTPS only. Exercises the production admission, loop, tools and atomic recovery. */
class TurnControlDeviceTest {
    @Test fun clarificationAndMidInferenceCorrectionResumeWithoutStaleWrites() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val inFlight=CountDownLatch(1);val release=CountDownLatch(1);val executions=AtomicInteger();val deliveries=AtomicInteger()
        val requests=java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        fun call(id:String,name:String,args:JSONObject)=JSONObject().put("id",id).put("type","function").put("function",JSONObject().put("name",name).put("arguments",args.toString()))
        fun reply(text:String="",vararg calls:JSONObject):MockResponse {
            val m=JSONObject().put("role","assistant").put("content",text)
            if(calls.isNotEmpty())m.put("tool_calls",JSONArray(calls.toList()))
            return MockResponse().setHeader("Content-Type","application/json").setBody(JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason",if(calls.isEmpty())"stop" else "tool_calls").put("message",m))).toString())
        }
        val server=MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(),false)
            dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                val body=JSONObject(request.body.readUtf8());requests+=body
                if(!body.has("tools")){deliveries.incrementAndGet();return reply("result.txt 已创建并核对内容。")}
                return when(executions.incrementAndGet()) {
                    1 -> reply("",call("ask","ask_user",JSONObject().put("question","使用哪种文件格式？").put("options",JSONArray(listOf("纯文本","JSON")))),call("after-question","write_file",JSONObject().put("path","must-not-exist.txt").put("content","wrong")))
                    2 -> {inFlight.countDown();check(release.await(10,TimeUnit.SECONDS));reply("",call("stale","write_file",JSONObject().put("path","obsolete.txt").put("content","wrong")))}
                    3 -> reply("",call("write","write_file",JSONObject().put("path","result.txt").put("content","verified fixture")))
                    4 -> reply("",call("verify","read_file",JSONObject().put("path","result.txt")))
                    5 -> reply("",call("assessment","task_state",JSONObject().put("action","finish").put("outcome","completed").put("summary","Created result.txt and read back its contents").put("evidence",JSONArray(listOf("write","verify")))))
                    6 -> reply("Created and verified result.txt.")
                    else -> MockResponse().setResponseCode(400)
                }
            }}
            start()
        }
        val client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).callTimeout(15,TimeUnit.SECONDS).build()
        val scenario=ActivityScenario.launch(MainActivity::class.java);lateinit var vm:CapriModel
        scenario.onActivity{vm=ViewModelProvider(it)[CapriModel::class.java]}
        val http=CapriModel::class.java.getDeclaredField("http").apply{isAccessible=true};val original=http.get(vm)
        val chat=Session(title="Turn control isolated fixture")
        val account=ModelAccount(provider="custom",name="Control fixture ${UUID.randomUUID()}",endpoint=server.url("/").toString(),key="fixture-not-a-real-key",protocol=ModelProtocol.CHAT,models=listOf(ModelOption("fixture",context=131072,output=4096,source="manual")))
        fun awaitIdle(){val deadline=System.nanoTime()+15_000_000_000L;while(vm.jobs.containsKey(chat.id)&&System.nanoTime()<deadline)Thread.sleep(25);assertFalse("Fixture task did not settle",vm.jobs.containsKey(chat.id))}
        fun send(text:String){ins.runOnMainSync{assertTrue(vm.sendTo(chat.id,text))}}
        try {
            assumeTrue("Run the isolated control case when user tasks are idle",vm.jobs.isEmpty())
            http.set(vm,client)
            ins.runOnMainSync{vm.sessions.add(chat);vm.store.models.save(account);vm.store.models.select(chat.id,account.id,"fixture");vm.persist()}
            ins.runOnMainSync {
                assertTrue(vm.sendTo(chat.id,"Create a report file; preserve existing files"))
                assertTrue(vm.sendTo(chat.id,"Do not use binary output"))
            }
            awaitIdle()
            assertEquals("WAITING",vm.turnView(chat.id).getString("phase"));assertEquals(0,deliveries.get())
            assertFalse(vm.store.workspace.file(chat.id,"must-not-exist.txt").exists())
            val recovery=AgentRecovery(File(ins.targetContext.filesDir,"agent-recovery"));val saved=requireNotNull(recovery.load(HistoryEditing.actualContextKey(chat)))
            assertEquals("使用哪种文件格式？",TurnState.question(saved)!!.getString("question"))
            assertEquals("Create a report file; preserve existing files",TaskState.snapshot(saved).getString("request"))
            val firstUsers=requests.first().getJSONArray("messages")
            assertEquals(1,(0 until firstUsers.length()).count { firstUsers.getJSONObject(it).optString("role")=="user" && firstUsers.getJSONObject(it).optString("content")=="Do not use binary output" })
            send("纯文本")
            assertTrue("Second request did not start",inFlight.await(10,TimeUnit.SECONDS))
            send("不要写 obsolete.txt，改成 result.txt")
            release.countDown();awaitIdle()
            assertEquals("COMPLETED",vm.turnView(chat.id).getString("phase"))
            assertEquals("verified fixture",vm.store.workspace.file(chat.id,"result.txt").readText())
            assertFalse(vm.store.workspace.file(chat.id,"obsolete.txt").exists());assertFalse(vm.store.workspace.file(chat.id,"must-not-exist.txt").exists())
            assertEquals(6,executions.get());assertEquals(1,deliveries.get())
            assertTrue(requests.filter{it.has("tools")}[2].toString().contains("不要写 obsolete.txt"))
            assertEquals(listOf("steering","answer","steering"),chat.messages.filter{it.control!=null}.map{it.control})
            assertNull(recovery.load(HistoryEditing.actualContextKey(chat)))
        } finally {
            release.countDown()
            ins.runOnMainSync{vm.jobs.remove(chat.id)?.cancel();http.set(vm,original);vm.store.models.forgetChat(chat.id);vm.store.models.remove(account.id);vm.sessions.removeAll{it.id==chat.id};vm.persist()}
            AgentRecovery(File(ins.targetContext.filesDir,"agent-recovery")).clear(HistoryEditing.actualContextKey(chat))
            vm.store.workspace.root(chat.id).deleteRecursively();scenario.close();server.shutdown()
        }
    }
}
