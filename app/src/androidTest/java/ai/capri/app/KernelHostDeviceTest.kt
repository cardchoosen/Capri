package ai.capri.app

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ai.capri.app.model.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Host driver uses the real authenticated USB/Web path while the screen is off. No provider calls leave localhost. */
class KernelHostDeviceTest {
    @Test fun headlessStandbyAdmitsScreenOffWebTask() {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val ready=File(context.cacheDir,"kernel-fixture-ready.json");val release=File(context.cacheDir,"kernel-fixture-release")
        ready.delete();release.delete()
        ins.runOnMainSync{ContextCompat.startForegroundService(context,Intent(context,AgentTaskService::class.java))}
        lateinit var vm:CapriModel
        val start=System.nanoTime()
        while(System.nanoTime()-start<10_000_000_000L){
            var available=false
            ins.runOnMainSync{AgentRuntime.current()?.let{if(it.taskEngine.hostAvailable){vm=it;available=true}}}
            if(available)break
            Thread.sleep(25)
        }
        ins.runOnMainSync{assertTrue(vm.taskEngine.hostAvailable);assertTrue(vm.jobs.isEmpty());assertFalse(vm.taskEngine.cpuLeaseHeld);assertTrue(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).isEmpty())}
        val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val calls=AtomicInteger()
        val server=MockWebServer().apply{
            useHttps(tls.sslSocketFactory(),false)
            dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                calls.incrementAndGet()
                val limit=System.nanoTime()+45_000_000_000L
                while(!release.exists() && System.nanoTime()<limit)Thread.sleep(25)
                val message=JSONObject().put("role","assistant").put("content","{\"kernel_fixture\":\"ok\"}")
                return MockResponse().setHeader("Content-Type","application/json").setBody(JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("message",message).put("finish_reason","stop"))).toString())
            }}
            start()
        }
        val client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build()
        val field=CapriModel::class.java.getDeclaredField("http").apply{isAccessible=true};val original=field.get(vm)
        val chat=Session(title="Kernel headless isolated fixture")
        val account=ModelAccount(provider="custom",name="Kernel fixture ${UUID.randomUUID()}",endpoint=server.url("/").toString(),key="fixture-local-only",protocol=ModelProtocol.CHAT,models=listOf(ModelOption("kernel-fixture",context=131072,output=4096,source="manual")))
        try {
            ins.runOnMainSync{field.set(vm,client);vm.store.models.save(account);vm.sessions.add(chat);vm.store.models.select(chat.id,account.id,"kernel-fixture");vm.persist()}
            ready.writeText(JSONObject().put("chat",chat.id).toString())
            val deadline=System.nanoTime()+60_000_000_000L
            while(calls.get()==0 && System.nanoTime()<deadline)Thread.sleep(25)
            assertTrue("Web driver did not reach the local inference fixture",calls.get()>0)
            ins.runOnMainSync{assertTrue(vm.taskEngine.cpuLeaseHeld);assertTrue(vm.jobs.containsKey(chat.id))}
            while(!release.exists() && System.nanoTime()<deadline)Thread.sleep(25)
            while(vm.jobs.containsKey(chat.id) && System.nanoTime()<deadline)Thread.sleep(25)
            ins.runOnMainSync {
                assertFalse("Screen-off Web task did not settle",vm.jobs.containsKey(chat.id))
                assertTrue(chat.messages.any{it.role=="assistant"&&it.text.contains("kernel_fixture")})
                assertTrue(vm.taskEngine.hostAvailable);assertFalse(vm.taskEngine.cpuLeaseHeld)
            }
            assertEquals("One task must not replay inference",1,calls.get())
        }finally{
            release.writeText("release")
            ins.runOnMainSync{vm.jobs[chat.id]?.cancel();field.set(vm,original);vm.store.models.forgetChat(chat.id);vm.store.models.remove(account.id);vm.sessions.removeAll{it.id==chat.id};vm.persist()}
            server.shutdown();ready.delete();release.delete()
        }
    }
}
