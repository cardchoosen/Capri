package ai.capri.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import android.content.Intent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapabilityDeviceTest {
    @Test fun ubuntuBaseBootstrapsBeforeAnySessionReset() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = PythonRuntime(context, Store(context).workspace)
        runtime.environment.ensureBase()
        assertTrue(runtime.ready)
    }
    @Test fun sharedPackagesAndIndependentSessionsExecuteRealPrograms() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context)
        val runtime = PythonRuntime(context, store.workspace)
        val tool = CapabilityTool(context, runtime)
        val chat = "ubuntu-test-${java.util.UUID.randomUUID()}"
        val other = "$chat-other"
        try {
            runtime.environment.ensureBase()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            withTimeout(180000) { while (!LegacySessionReset.marker(context).isFile) delay(100) }
            runtime.ensureSession(chat); runtime.ensureSession(other)
            val prepared = tool.execute(chat, JSONObject("""{"action":"prepare","requirements":{"system_packages":["g++","nodejs"],"python_packages":["pillow"],"imports":["PIL.Image"],"commands":["g++","node"],"headers":["vector"],"compiler":"g++"}}"""))
            assertTrue(prepared, JSONObject(prepared).optBoolean("requirements_satisfied"))
            val result = runtime.execute(chat, """
                from PIL import Image
                import subprocess,pathlib,ssl
                pathlib.Path('/workspace/main.cpp').write_text('#include <iostream>\nint main(){std::cout << 42;}')
                subprocess.run(['g++','/workspace/main.cpp','-o','/workspace/a.out'],check=True)
                assert subprocess.check_output(['/workspace/a.out']) == b'42'
                assert subprocess.check_output(['node','-p','6*7']).strip() == b'42'
                Image.new('RGB',(8,8),'red').save('/workspace/proof.gif')
                assert Image.open('/workspace/proof.gif').format == 'GIF'
                pathlib.Path('/root/private-proof').write_text('only-this-chat')
                print('compile/node/gif/TLS verified',ssl.OPENSSL_VERSION)
            """.trimIndent())
            assertTrue(result, result.startsWith("exit=0"))
            val isolated = runtime.execute(other, "import pathlib,importlib.util; assert not pathlib.Path('/root/private-proof').exists(); assert not pathlib.Path('/workspace/main.cpp').exists(); assert importlib.util.find_spec('PIL') is None; print('isolated')")
            assertTrue(isolated, isolated.startsWith("exit=0"))
            val terminals = ai.capri.app.terminal.TerminalManager.get(context)
            suspend fun terminal(action: String, params: JSONObject = JSONObject()): JSONObject {
                val response = JSONObject(terminals.execute(chat, JSONObject().put("action", action).put("params", params)))
                assertEquals(response.toString(), "ok", response.optString("status"))
                return response.getJSONObject("data")
            }
            val id = terminal("open").getString("terminal_id")
            fun params() = JSONObject().put("terminal_id", id)
            suspend fun finished(command: String): JSONObject = withTimeout(15000) {
                var data: JSONObject
                do { delay(100); data = terminal("read",params().put("command_id", command)) } while (!data.getBoolean("complete"))
                data
            }
            try {
                val one = terminal("run",params().put("command","export PROOF=shared-shell; cd /tmp").put("wait_ms",0)).getString("command_id")
                finished(one)
                val two = terminal("run",params().put("command","echo $" + "PROOF; pwd").put("wait_ms",0)).getString("command_id")
                val output = finished(two).getString("output")
                assertTrue(output, output.contains("shared-shell") && output.contains("/tmp"))
                val long = terminal("run",params().put("command","sleep 30").put("wait_ms",0)).getString("command_id")
                delay(200)
                terminal("cancel",params().put("command_id",long))
                assertEquals("cancelled", finished(long).getJSONObject("command").getString("state"))
            } finally { terminal("close",params()); terminal("delete",params()) }

        } finally { RuntimeFiles.delete(store.workspace.root(chat)); RuntimeFiles.delete(store.workspace.root(other)) }
    }
}
