package ai.capri.app

import ai.capri.app.terminal.TerminalManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExecutionToolsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun foreground() = instrumentation.runOnMainSync {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    @Test fun shortTasksAndVerificationRetainActualFailureAndBlockLaterSteps() = runBlocking<Unit> {
        foreground()
        val workspace = Store(context).workspace; val runtime = PythonRuntime(context, workspace)
        val chat = "execution-contract-${UUID.randomUUID()}"
        val environment = CapabilityTool(context, runtime); val short = ShortTaskTool(runtime, environment)
        try {
            val result = JSONObject(short.execute(chat, JSONObject().put("requirements", JSONObject().put("commands", JSONArray(listOf("python3"))).put("imports", JSONArray(listOf("json"))))
                .put("code", "import sys; assert sys.prefix == '/root/.venv'; print('short-task-ok')")))
            assertEquals(result.toString(), "ok", result.getString("status")); assertTrue(result.getString("output").contains("short-task-ok"))
            val failed = JSONObject(short.execute(chat, JSONObject("""{"steps":[{"name":"fail","argv":["python3","-c","raise SystemExit(9)"]},{"name":"blocked","argv":["touch","/workspace/should-not-run"]}]}""")))
            assertEquals("execution_failed", failed.getString("status")); assertEquals(9, failed.getInt("exit_code")); assertEquals(0, failed.getInt("failed_step"))
            assertEquals("execution_failed", failed.getJSONObject("error").getString("code")); assertFalse(workspace.file(chat, "should-not-run").exists())
            val verified = JSONObject(environment.execute(chat, JSONObject("""{"action":"verify","command":["python3","-c","raise SystemExit(7)"],"timeout_seconds":5}""")))
            assertEquals(verified.toString(), "verification_failed", verified.getString("status")); assertEquals(7, verified.getJSONObject("verification").getInt("exit_code"))
            assertEquals("verification_failed", verified.getJSONObject("error").getString("code"))
            assertTrue(RuntimeGate.active(context).none { it.chat == chat })
        } finally { RuntimeFiles.delete(workspace.root(chat)) }
    }
    @Test fun escapedLogsArePagedWithoutLossAndShellStatePersists() = runBlocking<Unit> {
        foreground()
        val workspace = Store(context).workspace; val chat = "terminal-json-${UUID.randomUUID()}"
        val manager = TerminalManager.get(context); var id: String? = null
        suspend fun call(action: String, extra: JSONObject = JSONObject()): JSONObject {
            val args = JSONObject(extra.toString()).put("action", action); id?.let { args.put("terminal_id", it) }
            val raw = manager.execute(chat, args); assertTrue(raw.length <= 36000)
            val response = JSONObject(raw); assertEquals(raw, "ok", response.getString("status")); return response.getJSONObject("data")
        }
        try {
            id = call("open", JSONObject().put("title", "Execution contract fixture")).getString("terminal_id")
            val bad = JSONObject(manager.execute(chat, JSONObject().put("action", "run").put("terminal_id", id).put("command", "touch /workspace/should-not-run").put("wait_ms", 30000)))
            assertEquals("invalid_argument", bad.getString("status")); assertTrue(bad.getString("message").contains("No command was submitted"))
            assertFalse(workspace.file(chat, "should-not-run").exists())
            assertEquals(0, call("attach").getInt("command_count"))
            val expected = "\u0001\"\\\u2028😀".repeat(4000)
            var data = call("run", JSONObject().put("command", "export CAPRI_ENV_PROOF=kept; cd /tmp; python3 -c 'import sys; sys.stdout.write((chr(1)+chr(34)+chr(92)+chr(0x2028)+chr(0x1f600))*4000)'"))
            val command = data.getString("command_id"); val recovered = StringBuilder()
            withTimeout(15000) {
                while (true) {
                    recovered.append(data.getString("output"))
                    if (data.getBoolean("complete") && !data.getBoolean("has_more")) break
                    data = call("read", JSONObject().put("command_id", command).put("offset", data.getInt("next_offset")).put("wait_ms", 10000))
                }
            }
            assertEquals(expected, recovered.toString())
            var state = call("run", JSONObject().put("command", "printf '%s\\n' \"$" + "CAPRI_ENV_PROOF\"; pwd"))
            val output = StringBuilder(state.getString("output"))
            withTimeout(10000) { while (!state.getBoolean("complete") || state.getBoolean("has_more")) {
                state = call("read", JSONObject().put("command_id", state.getJSONObject("command").getString("command_id")).put("offset", state.getInt("next_offset")).put("wait_ms", 10000))
                output.append(state.getString("output"))
            } }
            assertTrue(output.toString(), output.contains("kept") && output.contains("/tmp"))
        } finally {
            id?.let { call("close"); call("delete") }; RuntimeFiles.delete(workspace.root(chat))
        }
    }
}
