package ai.capri.app.nativebridge

import ai.capri.app.*
import ai.capri.app.extensions.ExtensionManager
import android.app.Application
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class RootServiceDeviceTest {
    @Test fun boundedServiceAndFridaAndroidSessionLifecycle() = runBlocking {
        val ins=InstrumentationRegistry.getInstrumentation();val app=ins.targetContext.applicationContext as Application
        val activity=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val workspace=Workspace(File(app.filesDir,"sessions"));val chat="frida-smoke-${UUID.randomUUID()}"
        val folder=workspace.folder(chat,"workspace");val tool=AndroidTool(app)
        val manager=ExtensionManager(app,PythonRuntime(app,workspace),workspace)
        val fixture=Intent().setClassName(app,"ai.capri.app.FridaFixtureService")
        suspend fun service(action:String,params:JSONObject,owner:String=chat)=JSONObject(tool.execute(JSONObject().put("action",action).put("params",params),folder,owner))
        fun operation(name:String,id:String)=JSONObject().put("operation",name).put("service_id",id)
        try {
            val auth=JSONObject(tool.execute(JSONObject().put("action","root_request")))
            assertEquals(auth.toString(),"ok",auth.getString("status"))
            val start=JSONObject().put("operation","start").put("request_key","service-control").put("command","printf ready; sleep 90").put("lifetime_seconds",30)
            val started=service("root_service",start);assertEquals(started.toString(),"ok",started.getString("status"))
            val id=started.getJSONObject("data").getString("service_id")
            assertEquals(id,service("root_service",start).getJSONObject("data").getString("service_id"))
            assertEquals("invalid_argument",service("root_service",JSONObject(start.toString()).put("command","echo duplicate")).getString("status"))
            assertEquals("invalid_argument",service("root_service",operation("status",id),"other-scope").getString("status"))
            val short=JSONObject(tool.execute(JSONObject().put("action","root_exec").put("params",JSONObject().put("command","printf independent")),folder))
            assertEquals(short.toString(),"ok",short.getString("status"))
            assertEquals("running",service("root_service",operation("status",id)).getJSONObject("data").getString("state"))
            val stopped=service("root_service",operation("stop",id));assertTrue(stopped.toString(),stopped.getJSONObject("data").getBoolean("cleanup_verified"))
            assertTrue(service("root_service",operation("stop",id)).getJSONObject("data").getBoolean("cleanup_verified"))

            // Test binary is provisioned explicitly by the developer, never auto-downloaded by a tool.
            val binary=File(app.cacheDir,"frida-smoke-server")
            assertTrue("Provision cache/frida-smoke-server with the pinned Android ARM64 release.",binary.isFile)
            binary.copyTo(File(folder,"server"),overwrite=true)
            assertTrue("Enable REA before running this test.",manager.rea.enabled)
            manager.activate(chat,"capri-rea-runtime")
            val frida=service("frida_service",JSONObject().put("operation","start").put("request_key","frida-control").put("server_path","server").put("lifetime_seconds",180))
            assertEquals(frida.toString(),"ok",frida.getString("status"))
            val fridaId=frida.getJSONObject("data").getString("service_id")
            assertTrue(frida.toString(),frida.getJSONObject("data").getBoolean("server_version_verified"))
            assertFalse(frida.toString().contains("token"))
            ins.runOnMainSync { app.startService(fixture) }
            suspend fun call(name:String,args:JSONObject=JSONObject()):JSONObject {
                val raw=manager.call(chat,ExtensionManager.toolName("capri-rea-runtime",name),args)
                var result=JSONObject(raw)
                if(result.optBoolean("truncated") && result.has("result_file"))result=JSONObject(workspace.file(chat,result.getString("result_file")).readText())
                assertFalse(result.toString().take(2000),result.optBoolean("isError"));assertFalse(result.toString().take(2000),result.optString("status")=="mcp_call_failed")
                return result
            }
            fun structured(result:JSONObject):JSONObject = result.optJSONObject("structuredContent") ?: run {
                val contents=result.getJSONArray("content")
                (0 until contents.length()).mapNotNull { runCatching { JSONObject(contents.getJSONObject(it).optString("text")) }.getOrNull() }.first()
            }
            val pidFile=File(app.cacheDir,"frida-fixture.pid")
            withTimeout(10000) { while(!pidFile.isFile)delay(50) }
            val pid=pidFile.readText().trim().toInt()
            assertTrue(pid>0 && pid!=android.os.Process.myPid())
            val discovery=call("list_frida_processes")
            File(app.cacheDir,"frida-smoke-discovery.json").writeText(discovery.toString())
            assertTrue(discovery.toString().take(1200),discovery.toString().contains(pid.toString()))
            val attached=structured(call("start_frida_session",JSONObject().put("mode","attach").put("pid",pid)))
            // REA returns Evidence envelopes; find sessionId without depending on wrapper layout.
            val session=Regex("\"(?:sessionId|session_id)\"\\s*:\\s*\"([^\"]+)\"").find(attached.toString())?.groupValues?.get(1) ?: error(attached.toString().take(2000))
            val loaded=call("load_frida_script",JSONObject().put("session_id",session).put("source_kind","inline").put("source","send({capri_fixture:true,pid:Process.id});"))
            val script=Regex("\"script_id\"\\s*:\\s*\"([^\"]+)\"").find(structured(loaded).toString())!!.groupValues[1]
            val status=call("frida_session_status",JSONObject().put("session_id",session))
            assertTrue(status.toString().take(2000),status.toString().contains("capri_fixture"))
            call("unload_frida_script",JSONObject().put("session_id",session).put("script_id",script))
            call("close_frida_session",JSONObject().put("session_id",session))
            val cleanup=service("frida_service",operation("stop",fridaId))
            assertTrue(cleanup.toString(),cleanup.getJSONObject("data").getBoolean("cleanup_verified"))
            assertNull(tool.rootServices.remote(chat))
            val cancelled=service("root_service",JSONObject(start.toString()).put("request_key","cancel-control"))
            val cancelId=cancelled.getJSONObject("data").getString("service_id")
            tool.rootServices.stopOwner(chat)
            assertTrue(service("root_service",operation("status",cancelId)).getJSONObject("data").getBoolean("cleanup_verified"))
            val failedParams=JSONObject(start.toString()).put("request_key","failed-control").put("command","exit 7")
            val failed=service("root_service",failedParams)
            assertEquals(failed.toString(),"failed",failed.getString("status"))
            assertEquals("failed",service("root_service",failedParams).getString("status"))
            val expiry=service("root_service",JSONObject(start.toString()).put("request_key","expiry-control"))
            val expiryId=expiry.getJSONObject("data").getString("service_id")
            withTimeout(35000) {
                while(service("root_service",operation("status",expiryId)).getJSONObject("data").getString("state")=="running")delay(500)
            }
            val expired=service("root_service",operation("status",expiryId)).getJSONObject("data")
            assertEquals(expired.toString(),"expired",expired.getString("state"))
            assertTrue(expired.toString(),expired.getBoolean("cleanup_verified"))
        } finally {
            tool.rootServices.stopOwner(chat)
            manager.deleteChat(chat);manager.dispose()
            ins.runOnMainSync { app.stopService(fixture) }
            SessionDeletion(app.filesDir).cleanup(chat);activity.close()
        }
    }
}
