package ai.capri.app.terminal

import ai.capri.app.PythonRuntime
import ai.capri.app.Store
import ai.capri.app.pythonenv.PythonEnvironmentTool
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolRoundTripDeviceTest {
    @Test fun directOutputContinuationFailuresAndCancellationKeepTheirMeaning() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ai.capri.app/.MainActivity")).use { it.readBytes() }
        val chat = "tool-round-trip-test"
        val store = Store(context)
        val manager = TerminalManager.get(context)
        suspend fun call(action: String, params: JSONObject = JSONObject()): JSONObject {
            val result = JSONObject(manager.execute(chat, JSONObject().put("action", action).put("params", params)))
            assertEquals(result.toString(), "ok", result.getString("status"))
            return result.getJSONObject("data")
        }
        fun terminal(id: String) = JSONObject().put("terminal_id", id)
        try {
            PythonRuntime(context, store.workspace).install()
            val id = call("open").getString("terminal_id")
            suspend fun run(code: String, wait: Int = 10000) = call("run", terminal(id)
                .put("command", code).put("wait_ms", wait))
            val short = run("printf 'short-output\\n'")
            assertTrue(short.toString(), short.getBoolean("complete"))
            assertFalse(short.getBoolean("has_more"))
            assertEquals("short-output\r\n", short.getString("output"))
            assertEquals(0, short.getJSONObject("command").getInt("exit_code"))
            val failed = run("(printf 'failure-output\\n' >&2; exit 7)")
            assertTrue(failed.getBoolean("complete"))
            assertEquals("failed", failed.getJSONObject("command").getString("state"))
            assertEquals(7, failed.getJSONObject("command").getInt("exit_code"))
            assertTrue(failed.getString("output").contains("failure-output"))

            // Run's first page and read's subsequent pages use the very same UTF-8 byte cursor.
            val paged = run("python3 -c \"import sys;sys.stdout.write('羯' * 12000)\"")
            assertTrue(paged.getBoolean("complete")); assertTrue(paged.getBoolean("has_more"))
            var data = paged
            val text = StringBuilder(data.getString("output"))
            while (data.getBoolean("has_more")) {
                val offset = data.getInt("next_offset")
                data = call("read", terminal(id).put("command_id", paged.getString("command_id")).put("offset", offset))
                assertEquals(offset, data.getInt("offset"))
                text.append(data.getString("output"))
            }
            assertEquals("羯".repeat(12000), text.toString())
            val running = run("printf 'first-part\\n'; sleep 3; printf 'last-part\\n'", 1000)
            assertFalse(running.toString(), running.getBoolean("complete"))
            assertEquals("first-part\r\n", running.getString("output"))
            val tail = call("read", terminal(id).put("command_id", running.getString("command_id"))
                .put("offset", running.getInt("next_offset")).put("wait_ms", 20000))
            assertEquals("last-part\r\n", tail.getString("output"))
            // read may return the last bytes before the supervisor commits exit status.
            withTimeout(5000) {
                var end = tail
                while (!end.getBoolean("complete")) end = call("read", terminal(id)
                    .put("command_id", running.getString("command_id")).put("offset", end.getInt("next_offset")).put("wait_ms", 1000))
                assertEquals(0, end.getJSONObject("command").getInt("exit_code"))
            }
            val long = run("printf 'before-cancel\\n'; sleep 60", 1000)
            assertFalse(long.getBoolean("complete"))
            assertTrue(long.getString("output").contains("before-cancel"))
            call("cancel", terminal(id).put("command_id", long.getString("command_id")))
            withTimeout(10000) {
                var end: JSONObject
                do { end = call("read", terminal(id).put("command_id", long.getString("command_id"))
                    .put("offset", long.getInt("next_offset")).put("wait_ms", 1000)) } while (!end.getBoolean("complete"))
                assertEquals(end.toString(), "cancelled", end.getJSONObject("command").getString("state"))
            }
            assertTrue(run("echo reused-after-cancel").getString("output").contains("reused-after-cancel"))

            val env = PythonEnvironmentTool(manager)
            val listed = JSONObject(env.execute(chat, JSONObject().put("action", "list").put("wait_ms", 10000))).getJSONObject("data")
            assertTrue(listed.getBoolean("complete")); assertFalse(listed.getBoolean("has_more"))
            assertTrue(listed.getString("output").contains("pip")); assertFalse(listed.has("read_with"))
            val status = JSONObject(env.execute(chat, JSONObject().put("action", "status").put("wait_ms", 10000))).getJSONObject("data")
            assertTrue(status.getBoolean("complete")); assertFalse(status.getBoolean("has_more"))
            assertTrue(status.getString("output").contains("bundled_alpine_packages"))
        } finally {
            manager.list(chat).forEach { record ->
                val params = terminal(record.getString("terminal_id"))
                call("close", params); call("delete", params)
            }
            store.workspace.root(chat).deleteRecursively()
        }
    }
}
