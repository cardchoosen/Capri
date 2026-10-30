package ai.capri.app

import ai.capri.app.program.*
import ai.capri.app.agent.ToolRegistry
import ai.capri.app.agent.CompletionEvidence
import ai.capri.app.files.FileToolContract
import ai.capri.app.files.WorkspaceFileTools
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProgramDeviceTest {
    @Test fun persistentKernelRpcIsolationLimitsAndReceipts()=runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"program-${UUID.randomUUID()}").apply { mkdirs() }
        val chat="programfixture";val workspace=Workspace(File(root,"sessions"));val runtime=PythonRuntime(context,workspace)
        assertTrue("Existing runtime must be ready; this fixture never downloads it.",runtime.ready)
        val launcher=ProgramRuntime(context,runtime,workspace);val engine=ProgramEngine(File(root,"receipts"))
        val tools=WorkspaceFileTools(workspace);val registry=ToolRegistry(JSONArray(FileToolContract.specifications()))
        val host=object:ProgramEngine.Host {
            override suspend fun launch(chat:String)=launcher.launch(chat)
            override fun stop(process:Process)=OwnedProcesses.stop(process)
            override fun fingerprint(chat:String)=launcher.fingerprint(chat)
            override suspend fun canExecute()=true
            override suspend fun call(call:JSONObject):String {
                val f=call.getJSONObject("function");val name=f.getString("name");val args=JSONObject(f.getString("arguments"));registry.validate(name,args)
                val lease=RuntimeGate.beginFiles(context,chat,"Program fixture")
                return try { tools.execute(chat,name,args) } finally { RuntimeGate.endFiles(lease) }
            }
        }
        try {
            workspace.file(chat,"numbers.json").writeText("[1,2,3,4]")
            val start=android.os.SystemClock.elapsedRealtime()
            val first=JSONObject(engine.execute(chat,JSONObject().put("action","execute").put("timeout_ms",60000).put("code","import json,resource\ndata=capri.call('read_file',{'path':'numbers.json'})\nvalues=json.loads(data['content'])\ncapri.emit({'total':sum(values),'address_limit':resource.getrlimit(resource.RLIMIT_AS)[1]})"),host,"parent-1"))
            assertEquals(first.toString(),"ok",first.getString("status"))
            assertEquals(10,first.getJSONArray("emitted").getJSONObject(0).getInt("total"))
            assertEquals(384L*1024*1024,first.getJSONArray("emitted").getJSONObject(0).getLong("address_limit"))
            assertFalse(first.toString().contains("numbers.json"))
            val second=JSONObject(engine.execute(chat,JSONObject().put("action","execute").put("code","capri.emit(sum(values)+1)"),host,"parent-2"))
            assertEquals("first=$first second=$second","ok",second.getString("status"));assertTrue(second.getBoolean("reused"));assertEquals(11,second.getJSONArray("emitted").getInt(0))
            val batch=JSONObject(engine.execute(chat,JSONObject().put("action","batch").put("calls",JSONArray()
                .put(JSONObject().put("name","read_file").put("arguments",JSONObject().put("path","numbers.json")))
                .put(JSONObject().put("name","read_file").put("arguments",JSONObject().put("path","missing.json")))),host))
            assertEquals("partial",batch.getString("status"));assertEquals(2,batch.getJSONArray("results").length())
            val memory=JSONObject(engine.execute(chat,JSONObject().put("action","execute").put("code","value=bytearray(500*1024*1024)"),host))
            assertEquals("error",memory.getString("status"));assertTrue(CompletionEvidence.unsuccessful(memory.toString()))
            engine.execute(chat,JSONObject().put("action","execute").put("code","value=1"),host)
            val timeout=JSONObject(engine.execute(chat,JSONObject().put("action","execute").put("code","while True: pass").put("timeout_ms",500),host))
            assertEquals("timeout",timeout.getString("status"))
            val receipt=JSONObject(engine.execute(chat,JSONObject().put("action","read").put("run_id",first.getString("run_id")),host))
            assertTrue(receipt.getString("text").contains("parent-1"))
            try { engine.execute("other",JSONObject().put("action","read").put("run_id",first.getString("run_id")),host);fail() } catch(_:java.io.FileNotFoundException) {}
            assertEquals("absent",JSONObject(engine.execute(chat,JSONObject().put("action","status"),host)).getString("kernel_state"))
            android.util.Log.i("ProgramFixture",JSONObject().put("fixture_ms",android.os.SystemClock.elapsedRealtime()-start).put("first_ms",first.getLong("duration_ms"))
                .put("reused_ms",second.getLong("duration_ms")).put("rpc_calls",first.getInt("rpc_calls")).put("provider_calls",0).toString())
        } finally { engine.dispose();root.deleteRecursively() }
    }
}
