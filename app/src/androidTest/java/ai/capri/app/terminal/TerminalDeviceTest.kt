package ai.capri.app.terminal

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.capri.app.MainActivity
import ai.capri.app.PythonRuntime
import ai.capri.app.Store
import ai.capri.app.ModelRequests
import ai.capri.app.AgentRecovery
import ai.capri.app.RecoveryMessages
import org.json.JSONArray
import java.io.IOException
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

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
    private fun httpStatus(port: Int): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
        socket.soTimeout = 3000
        socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
        socket.getOutputStream().flush()
        socket.getInputStream().bufferedReader().readLine().orEmpty()
    }
    private fun reachable(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    }.getOrDefault(false)
    @Test fun cleanupTestArtifacts() = runBlocking<Unit> {
        for (chat in listOf("terminal-device-first", "terminal-device-second", "terminal-recovery-test", "terminal-protocol-test", "terminal-wait-test")) {
            manager.list(chat).forEach { record ->
                val id = record.getString("terminal_id")
                if (record.getString("state") in setOf("starting", "ready", "running")) call(chat, "close", terminal(id))
                call(chat, "delete", terminal(id))
            }
            Store(context).workspace.root(chat).deleteRecursively()
        }
        File(context.filesDir, "terminal-recovery-test.json").delete()
    }
    @Test fun terminalsKeepShellStateInputAndCancelableLongTasks() = runBlocking<Unit> {
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
            val background = InstrumentationRegistry.getArguments().getString("terminal_background") == "true"
            if (background) {
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                println("CAPRI_TEST_STEP: background mode, home action returned")
            } else println("CAPRI_TEST_STEP: foreground mode")
            val seconds = InstrumentationRegistry.getArguments().getString("terminal_wait_seconds")?.toInt() ?: 63
            require(seconds in 1..90)
            delay(seconds * 1000L)
            println("CAPRI_TEST_STEP: long wait complete ($seconds seconds)")
            val stillRunning = call(firstChat, "read", terminal(first).put("command_id", server))
            assertFalse(stillRunning.toString(), stillRunning.getBoolean("complete"))
            println("CAPRI_TEST_STEP: long command confirmed running")
            val response = httpStatus(port)
            println("CAPRI_TEST_STEP: localhost HTTP returned")
            assertTrue(response, response.contains("200"))
            call(firstChat, "cancel", terminal(first).put("command_id", server))
            println("CAPRI_TEST_STEP: cancellation submitted")
            assertEquals("cancelled", settled(firstChat, first, server).getJSONObject("command").getString("state"))
            withTimeout(3000) { while (reachable(port)) delay(80) }
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
            foreground(); delay(400)
            val childTerminal = call(secondChat, "open").getString("terminal_id").also { created += secondChat to it }
            val readyFile = "/tmp/child-$childTerminal.ready"
            val child = run(secondChat, childTerminal, "python3 -u -c 'from http.server import HTTPServer,SimpleHTTPRequestHandler; from pathlib import Path; s=HTTPServer((\"127.0.0.1\",0),SimpleHTTPRequestHandler); print(\"child-port:\",s.server_port,flush=True); Path(\"$readyFile\").write_text(\"ready\"); s.serve_forever()' & while [ ! -f $readyFile ]; do sleep 0.1; done")
            val childOutput = settled(secondChat, childTerminal, child).getString("output")
            val childPort = Regex("child-port: (\\d+)").find(childOutput)!!.groupValues[1].toInt()
            assertTrue(reachable(childPort))
            call(secondChat, "close", terminal(childTerminal))
            withTimeout(5000) { while (reachable(childPort)) delay(80) }
            println("CAPRI_TEST_STEP: close released background child port")
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

    @Test fun waitWindowsYieldToOutputCancelAndPreserveRecoveryResults() = runBlocking<Unit> {
        foreground(); delay(400)
        val store = Store(context)
        PythonRuntime(context, store.workspace).install()
        val chat = "terminal-wait-test"
        val id = call(chat, "open").getString("terminal_id")
        val recovery = AgentRecovery(File(context.filesDir, "agent-recovery"))
        try {
            val short = call(chat, "run", terminal(id).put("command", "sleep 0.4; echo short").put("wait_ms", 3000))
            assertEquals("completed", short.getJSONObject("command").getString("state"))
            val command = run(chat, id, "sleep 1; printf delayed; sleep 2")
            val started = System.nanoTime()
            val first = call(chat, "read", terminal(id).put("command_id", command).put("wait_ms", 20000))
            assertTrue(first.toString(), first.getString("output").contains("delayed"))
            assertFalse(first.getBoolean("complete"))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
            val end = call(chat, "read", terminal(id).put("command_id", command).put("offset", first.getInt("next_offset")).put("wait_ms", 20000))
            assertTrue(end.getBoolean("complete"))

            val sleeper = run(chat, id, "sleep 30")
            val waiting = async { call(chat, "read", terminal(id).put("command_id", sleeper).put("wait_ms", 20000)) }
            delay(150)
            val cancelStarted = System.nanoTime()
            call(chat, "cancel", terminal(id).put("command_id", sleeper))
            val canceled = withTimeout(4000) { waiting.await() }
            // PTY may emit a newline before the prompt publishes cancellation: output correctly wakes read first.
            val finalCancel = if (canceled.getBoolean("complete")) canceled else settled(chat, id, sleeper)
            assertEquals("cancelled", finalCancel.getJSONObject("command").getString("state"))
            assertTrue((System.nanoTime() - cancelStarted) / 1_000_000 < 4000)

            val survives = run(chat, id, "sleep 0.5; echo survives-read-cancel")
            assertNull(withTimeoutOrNull(100) { call(chat, "read", terminal(id).put("command_id", survives).put("wait_ms", 20000)) })
            val still = call(chat, "read", terminal(id).put("command_id", survives).put("wait_ms", 20000))
            assertTrue(still.getString("output").contains("survives-read-cancel"))
            settled(chat, id, survives)

            val policy = ModelRequests(pause = {})
            policy.execute { JSONObject().put("next", "tool") }
            val once = call(chat, "run", terminal(id).put("command", "echo once >> retry-proof.txt").put("wait_ms", 3000))
            assertEquals("completed", once.getJSONObject("command").getString("state"))
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "test"))
                .put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "once").put("function", JSONObject().put("name", "terminal")))))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "once").put("content", once.toString()))
            recovery.save(chat, messages)
            var attempts = 0
            policy.execute { if (++attempts < 3) throw IOException("injected abort"); JSONObject() }
            assertEquals(1, store.workspace.file(chat, "retry-proof.txt").readLines().size)
            val loaded = AgentRecovery(File(context.filesDir, "agent-recovery")).load(chat)!!
            assertEquals(once.getString("command_id"), JSONObject(RecoveryMessages.forResume(loaded).getJSONObject(2).getString("content")).getString("command_id"))
            call(chat, "close", terminal(id))
            val closed = call(chat, "read", terminal(id).put("wait_ms", 20000).put("offset", call(chat, "read", terminal(id)).getInt("next_offset")))
            assertTrue(closed.getBoolean("complete"))
        } finally {
            call(chat, "close", terminal(id))
            call(chat, "delete", terminal(id))
            recovery.clear(chat)
            store.workspace.root(chat).deleteRecursively()
        }
    }

    @Test fun prepareProcessDeath() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("terminal_recovery") == "prepare")
        PythonRuntime(context, Store(context).workspace).install()
        foreground(); delay(500)
        val chat = "terminal-recovery-test"
        val id = call(chat, "open").getString("terminal_id")
        val command = run(chat, id, "echo recovery-marker; sleep 90", 120)
        delay(400)
        File(context.filesDir, "terminal-recovery-test.json").writeText(JSONObject().put("terminal_id", id).put("command_id", command).toString())
    }

    @Test fun verifyProcessDeathIsInterruptedNotReattached() = runBlocking<Unit> {
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
