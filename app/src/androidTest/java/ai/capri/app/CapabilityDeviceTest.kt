package ai.capri.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapabilityDeviceTest {
    @Test fun taskReadinessChecksRealExecutionAndHonorsNativeOnlyConstraint() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context); val runtime = PythonRuntime(context, store.workspace)
        val tool = CapabilityTool(runtime)
        val chat = "capability-test-${java.util.UUID.randomUUID()}"
        suspend fun check(language: String, outputs: List<String>, helpers: Boolean): JSONObject {
            val result = JSONObject(tool.execute(chat, JSONObject().put("language", language)
                .put("outputs", JSONArray(outputs)).put("allow_helpers", helpers)))
            assertEquals(result.toString(), "ok", result.getString("status"))
            return result
        }
        try {
            runtime.install()
            val absent = check("cpp", listOf("gif"), true)
            assertEquals(absent.toString(), "needs_install", absent.getString("readiness"))
            assertEquals("ready", absent.getJSONObject("checks").getJSONObject("language").getString("state"))
            assertEquals("pillow", absent.getJSONObject("install_if_needed").getJSONArray("packages").getString(0))
            val installed = runtime.execute(chat, "import subprocess\nsubprocess.run(['/root/.venv/bin/python3','-I','/opt/capri/bootstrap/python_packages.py','pillow'],check=True)")
            assertTrue(installed, installed.startsWith("exit=0"))
            val ready = check("cpp", listOf("gif", "png"), true)
            assertEquals(ready.toString(), "ready", ready.getString("readiness"))
            assertTrue(ready.getJSONObject("checks").getJSONObject("pillow").getString("evidence").contains("encode/decode"))
            val strict = check("cpp", listOf("gif"), false)
            assertEquals(strict.toString(), "needs_native_backend", strict.getString("readiness"))
            assertFalse(strict.getJSONObject("checks").has("pillow"))
            assertEquals("ready", check("node", emptyList(), false).getString("readiness"))
            assertEquals("ready", check("python", listOf("gif"), true).getString("readiness"))
            val leftover = runtime.execute(chat, "import glob\nassert not glob.glob('/tmp/capri-capability-*');print('temporary probes cleaned')")
            assertTrue(leftover, leftover.contains("temporary probes cleaned"))
        } finally { store.workspace.root(chat).deleteRecursively() }
    }
}
