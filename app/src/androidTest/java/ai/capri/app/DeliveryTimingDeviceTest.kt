package ai.capri.app

import ai.capri.app.agent.ModelTransport
import ai.capri.app.model.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Same local HTTPS model, ordinary voice delivery versus exact JSON. No live provider calls. */
class DeliveryTimingDeviceTest {
    @Test fun exactJsonAvoidsVoiceRequestAndTimingsIdentifyPurposeWithoutContent() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val execute=AtomicInteger();val voice=AtomicInteger()
        val exact="{\"ok\":true,\"marker\":\"never_log_this_value\"}"
        fun reply(text:String)=MockResponse().setHeader("Content-Type","application/json").setBody(JSONObject()
            .put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("role","assistant").put("content",text))))
            .put("usage",JSONObject().put("prompt_tokens",100).put("completion_tokens",10).put("total_tokens",110)).toString())
        val server=MockWebServer().apply {
            useHttps(tls.sslSocketFactory(),false)
            dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                val body=JSONObject(request.body.readUtf8())
                if(!body.has("tools")){voice.incrementAndGet();return reply("缓存可以复用已经处理过的结果。")}
                execute.incrementAndGet()
                return reply(if(body.getJSONArray("messages").toString().contains("只输出 JSON"))exact else "A cache reuses previously computed results.")
            }}
            start()
        }
        val client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).addInterceptor(ModelTransport.QueueTiming()).callTimeout(15,TimeUnit.SECONDS).build()
        val scenario=ActivityScenario.launch(MainActivity::class.java);lateinit var vm:CapriModel
        scenario.onActivity{vm=AgentRuntime.get(it.application)}
        val field=CapriModel::class.java.getDeclaredField("http").apply{isAccessible=true};val original=field.get(vm)
        val normal=Session(title="Delivery timing normal fixture");val strict=Session(title="Delivery timing JSON fixture");val chats=listOf(normal,strict)
        val account=ModelAccount(provider="custom",name="Delivery timing ${UUID.randomUUID()}",endpoint=server.url("/").toString(),key="timing-fixture-key",protocol=ModelProtocol.CHAT,models=listOf(ModelOption("fixture",context=131072,output=4096,source="manual")))
        fun send(c:Session,text:String){ins.runOnMainSync{assertTrue(vm.sendTo(c.id,text))};val deadline=System.nanoTime()+15_000_000_000L;while(vm.jobs.containsKey(c.id)&&System.nanoTime()<deadline)Thread.sleep(25);assertFalse(vm.jobs.containsKey(c.id));assertEquals("COMPLETED",vm.turnView(c.id).getString("phase"))}
        fun events(c:Session):List<JSONObject>{val file=File(vm.store.workspace.root(c.id),"metrics").listFiles()!!.maxBy{it.lastModified()};val text=file.readText();assertFalse(text.contains("timing-fixture-key"));assertFalse(text.contains("never_log_this_value"));return text.lineSequence().filter{it.isNotBlank()}.map(::JSONObject).toList()}
        try {
            assumeTrue("Run isolated delivery validation when user tasks are idle",vm.jobs.isEmpty());field.set(vm,client)
            ins.runOnMainSync{vm.store.models.save(account);chats.forEach{vm.sessions.add(it);vm.store.models.select(it.id,account.id,"fixture")};vm.persist()}
            send(normal,"解释缓存有什么用。")
            assertEquals(1,execute.get());assertEquals(1,voice.get())
            send(strict,"只输出 JSON，对象包含 ok 和 marker。")
            assertEquals(2,execute.get());assertEquals(1,voice.get());assertEquals(exact,strict.messages.last{it.role=="assistant"&&it.finalReply}.text)
            assertEquals(1,vm.turnView(strict.id).getJSONObject("budget").getInt("used"))
            val normalEvents=events(normal);val strictEvents=events(strict)
            assertEquals(listOf("execution","delivery"),normalEvents.filter{it.optString("stage")=="model_request"}.map{it.getString("purpose")})
            assertEquals(listOf("execution"),strictEvents.filter{it.optString("stage")=="model_request"}.map{it.getString("purpose")})
            assertEquals(2,normalEvents.count{it.optString("stage")=="model_queue"})
            assertTrue(strictEvents.any{it.optString("route")=="direct_exact_json"})
            assertTrue(normalEvents.any{it.optString("route")=="persona"})
            assertTrue(normalEvents.any{it.optString("stage")=="turn_prepare"})
            assertTrue(normalEvents.any{it.optString("stage")=="context_prepare"})
        } finally {
            ins.runOnMainSync{chats.forEach{vm.jobs.remove(it.id)?.cancel();vm.store.models.forgetChat(it.id);vm.sessions.removeAll{c->c.id==it.id}};field.set(vm,original);vm.store.models.remove(account.id);vm.persist()}
            chats.forEach{AgentRecovery(File(ins.targetContext.filesDir,"agent-recovery")).clear(HistoryEditing.actualContextKey(it));vm.store.workspace.root(it.id).deleteRecursively()};scenario.close();server.shutdown()
        }
    }
}
