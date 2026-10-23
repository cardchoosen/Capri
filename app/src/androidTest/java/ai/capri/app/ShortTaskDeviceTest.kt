package ai.capri.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import android.content.Intent
import java.io.File

class ShortTaskDeviceTest {
    @Before fun foreground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    @Test fun preparesDependenciesOnceAndBlocksExecutionWhenRequirementsFail() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context); val runtime = PythonRuntime(context, store.workspace)
        val tool = ShortTaskTool(runtime, CapabilityTool(context, runtime))
        val chat = "short-task-${java.util.UUID.randomUUID()}"
        try {
            runtime.ensureSession(chat)
            val request = JSONObject().put("requirements", JSONObject("""{"python_packages":["pillow"],"imports":["PIL.Image"]}"""))
                .put("code", "from PIL import Image; Image.new('RGB',(8,8),'red').save('/workspace/proof.gif'); print('ready')")
            val first = JSONObject(tool.execute(chat, request))
            assertEquals(first.toString(), "ok", first.getString("status"))
            assertTrue(first.toString(), first.getJSONObject("timings_ms").has("prepare"))
            assertTrue(first.toString(), first.getJSONObject("timings_ms").has("execute"))
            assertTrue(store.workspace.file(chat, "proof.gif").isFile)
            val again = JSONObject(CapabilityTool(context, runtime).execute(chat, JSONObject("""{"action":"prepare","requirements":{"python_packages":["pillow"],"imports":["PIL.Image"]}}""")))
            assertTrue(again.toString(), again.getJSONArray("operations").getJSONObject(0).getBoolean("reused"))
            val blocked = JSONObject(tool.execute(chat, JSONObject("""{"requirements":{"commands":["capri_absent_command"]},"code":"open('/workspace/should-not-run','w').write('bad')"}""")))
            assertEquals(blocked.toString(), "preparation_failed", blocked.getString("status"))
            assertFalse(store.workspace.file(chat, "should-not-run").exists())
        } finally { RuntimeFiles.delete(store.workspace.root(chat)) }
    }
    @Test fun stopsOnCompilerFailureAndCancellationWithoutReplayingEarlierSteps() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context); val runtime = PythonRuntime(context, store.workspace)
        val tool = ShortTaskTool(runtime, CapabilityTool(context, runtime))
        val chat = "short-task-${java.util.UUID.randomUUID()}"
        try {
            runtime.ensureSession(chat)
            store.workspace.file(chat, "broken.cpp").writeText("int main(){missing_symbol();}")
            val request = JSONObject("""{"steps":[{"name":"compile","argv":["g++","/workspace/broken.cpp","-o","/workspace/main"]},{"name":"run","argv":["/bin/sh","-c","touch /workspace/should-not-run"]}]}""")
            val failed = JSONObject(tool.execute(chat, request))
            assertEquals(failed.toString(), "execution_failed", failed.getString("status"))
            assertEquals(0, failed.getInt("failed_step")); assertEquals(1, failed.getJSONArray("steps").length())
            assertNotEquals(0, failed.getInt("exit_code")); assertFalse(store.workspace.file(chat, "should-not-run").exists())
            store.workspace.file(chat, "broken.cpp").writeText("int main(){return 0;}")
            val fixed = JSONObject(tool.execute(chat, request))
            assertEquals(fixed.toString(), "ok", fixed.getString("status"))
            assertTrue(store.workspace.file(chat, "should-not-run").isFile)
            val job = launch(Dispatchers.IO) { tool.execute(chat, JSONObject().put("code", "import pathlib,time;pathlib.Path('/workspace/started').touch();time.sleep(2);pathlib.Path('/workspace/late').touch()")) }
            withTimeout(10000) { while (!store.workspace.file(chat, "started").exists()) delay(50) }
            job.cancelAndJoin()
            assertTrue(RuntimeGate.active(context).none { it.chat == chat })
            delay(2500) // A surviving guest would have written this after cancellation.
            assertFalse(store.workspace.file(chat, "late").exists())
        } finally { RuntimeFiles.delete(store.workspace.root(chat)) }
    }
}
