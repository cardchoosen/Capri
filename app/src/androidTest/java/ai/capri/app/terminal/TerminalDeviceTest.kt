package ai.capri.app.terminal

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.capri.app.MainActivity
import ai.capri.app.PythonRuntime
import ai.capri.app.Store
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TerminalDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = TerminalManager.get(context)
    private fun foreground() {
        instrumentation.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    private suspend fun call(chat: String, action: String, params: JSONObject = JSONObject()): JSONObject {
        val response = JSONObject(manager.execute(chat, JSONObject().put("action", action).put("params", params)))
        assertEquals(response.toString(), "ok", response.optString("status"))
        return response.getJSONObject("data")
    }
    private fun terminal(id: String) = JSONObject().put("terminal_id", id)
    private suspend fun run(chat: String, id: String, code: String, timeout: Int = 60): String =
        call(chat, "run", terminal(id).put("command", code).put("timeout_seconds", timeout).put("wait_ms", 0)).getString("command_id")
    private suspend fun settled(chat: String, id: String, command: String): JSONObject = withTimeout(10_000) {
        var result: JSONObject
        do {
            delay(80)
            result = call(chat, "read", terminal(id).put("command_id", command))
        } while (!result.getBoolean("complete"))
        result
    }
    @Test fun terminalsKeepShellStateInputAndCancelableLongTasks() = runBlocking {
        val store = Store(context)
        PythonRuntime(context, store.workspace).install()
        foreground()
        delay(500)
        val firstChat = "terminal-device-first"
        val secondChat = "terminal-device-second"
        val created = mutableListOf<Pair<String, String>>()
        var primaryFailure: Throwable? = null
        try {
            val first = call(firstChat, "open").getString("terminal_id").also { created += firstChat to it }
            val second = call(secondChat, "open").getString("terminal_id").also { created += secondChat to it }
            val setup = settled(firstChat, first, run(firstChat, first, "mkdir -p nested; cd nested; export CAPRI_TEST=alpha; counter=7; helper() { echo function-ok; }; echo setup"))
            assertEquals(setup.toString(), "completed", setup.getJSONObject("command").getString("state"))
            val state = settled(firstChat, first, run(firstChat, first, "pwd; echo \"\$CAPRI_TEST:\$counter\"; helper"))
            assertTrue(state.toString(), state.getString("output").contains("/workspace/nested"))
            assertTrue(state.toString(), state.getString("output").contains("alpha:7"))
            assertTrue(state.toString(), state.getString("output").contains("function-ok"))
            val other = settled(secondChat, second, run(secondChat, second, "pwd; echo \"\${CAPRI_TEST-unset}\""))
            assertTrue(other.toString(), other.getString("output").contains("unset"))
            assertFalse(other.toString(), other.getString("output").contains("/workspace/nested"))
            val unauthorized = JSONObject(manager.execute(secondChat, JSONObject().put("action", "attach").put("params", terminal(first))))
            assertEquals("not_found", unauthorized.getString("status"))

            val input = run(firstChat, first, "read value; printf 'stdin:%s\\n' \"\$value\"")
            delay(200)
            call(firstChat, "input", terminal(first).put("command_id", input).put("text", "Capri PTY\n"))
            assertTrue(settled(firstChat, first, input).getString("output").contains("stdin:Capri PTY"))

            // Real localhost server remains alive beyond the previous 60-second short-script boundary.
            val server = run(firstChat, first, "python3 -u -c 'from http.server import HTTPServer,SimpleHTTPRequestHandler; s=HTTPServer((\"127.0.0.1\",0),SimpleHTTPRequestHandler); print(\"port:\",s.server_port,flush=True); s.serve_forever()'", 120)
            val ready = withTimeout(8000) {
                var data: JSONObject
                do { delay(100); data = call(firstChat, "read", terminal(first).put("command_id", server)) }
                while (!data.getString("output").contains("port:"))
                data
            }
            val port = Regex("port: (\\d+)").find(ready.getString("output"))!!.groupValues[1].toInt()
            println("CAPRI_TEST_STEP: server ready")
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            println("CAPRI_TEST_STEP: home action returned")
            delay(63_000)
            println("CAPRI_TEST_STEP: background wait complete")
            val stillRunning = call(firstChat, "read", terminal(first).put("command_id", server))
            assertFalse(stillRunning.toString(), stillRunning.getBoolean("complete"))
            println("CAPRI_TEST_STEP: long command confirmed running")
            val response = PythonRuntime(context, store.workspace).execute(secondChat, "import urllib.request\nprint(urllib.request.urlopen('http://127.0.0.1:$port',timeout=3).status)")
            println("CAPRI_TEST_STEP: localhost HTTP returned")
            assertTrue(response, response.contains("200"))
            call(firstChat, "cancel", terminal(first).put("command_id", server))
            println("CAPRI_TEST_STEP: cancellation submitted")
            assertEquals("cancelled", settled(firstChat, first, server).getJSONObject("command").getString("state"))
            assertEquals("completed", settled(firstChat, first, run(firstChat, first, "echo shell-survived")).getJSONObject("command").getString("state"))
            assertEquals("timeout", settled(firstChat, first, run(firstChat, first, "sleep 10", 1)).getJSONObject("command").getString("state"))

            val unicode = run(secondChat, second, "printf '摩羯\\n'")
            settled(secondChat, second, unicode)
            val part = call(secondChat, "read", terminal(second).put("command_id", unicode).put("limit", 4))
            assertEquals("摩", part.getString("output"))
            val tail = call(secondChat, "read", terminal(second).put("command_id", unicode).put("offset", part.getInt("next_offset")))
            assertTrue(tail.toString(), tail.getString("output").startsWith("羯"))

            call(secondChat, "clear_history", terminal(second))
            assertEquals(0, call(secondChat, "attach", terminal(second)).getInt("command_count"))
            val overflow = run(secondChat, second, "python3 -c 'print(\"x\"*2200000)' ")
            val capped = settled(secondChat, second, overflow)
            assertEquals("output_limit", capped.getJSONObject("command").getString("state"))
            assertTrue(capped.getLong("total_bytes") <= 2_000_000)
        } catch (failure: Throwable) { primaryFailure = failure; throw failure } finally {
            created.forEach { (chat, id) ->
                val diagnostic = File(context.filesDir, "sessions/$chat/terminals/$id/supervisor.log")
                if (diagnostic.exists() && diagnostic.length() > 0) println("TERMINAL_DIAGNOSTIC: " + diagnostic.readText().take(3000))
                try { call(chat, "close", terminal(id)); call(chat, "delete", terminal(id)) }
                catch (cleanup: Throwable) { if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup }
            }
            store.workspace.root(firstChat).deleteRecursively()
            store.workspace.root(secondChat).deleteRecursively()
            foreground()
        }
    }

    @Test fun prepareProcessDeath() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("terminal_recovery") == "prepare")
        PythonRuntime(context, Store(context).workspace).install()
        foreground(); delay(500)
        val chat = "terminal-recovery-test"
        val id = call(chat, "open").getString("terminal_id")
        val command = run(chat, id, "echo recovery-marker; sleep 90", 120)
        delay(400)
        File(context.filesDir, "terminal-recovery-test.json").writeText(JSONObject().put("terminal_id", id).put("command_id", command).toString())
    }

    @Test fun verifyProcessDeathIsInterruptedNotReattached() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("terminal_recovery") == "verify")
        val chat = "terminal-recovery-test"
        val proof = File(context.filesDir, "terminal-recovery-test.json")
        val saved = JSONObject(proof.readText())
        val id = saved.getString("terminal_id")
        try {
            val record = call(chat, "attach", terminal(id))
            assertEquals("interrupted", record.getString("state"))
            assertFalse(record.has("shell_pid"))
            val output = call(chat, "read", terminal(id).put("command_id", saved.getString("command_id")))
            assertEquals("interrupted", output.getJSONObject("command").getString("state"))
            assertTrue(output.getString("output").contains("recovery-marker"))
        } finally {
            call(chat, "delete", terminal(id))
            proof.delete()
            Store(context).workspace.root(chat).deleteRecursively()
        }
    }
}
