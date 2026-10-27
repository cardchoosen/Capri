package ai.capri.app.nativebridge

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Only identity and app-owned temporary files; never change network/system settings. */
@RunWith(AndroidJUnit4::class)
class RootDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private suspend fun authorizedTool(): AndroidTool {
        val tool = AndroidTool(app)
        val status = JSONObject(tool.execute(JSONObject().put("action", "root_status")))
        assertTrue(status.toString(), status.getJSONObject("data").getBoolean("su_detected"))
        val request = JSONObject(tool.execute(JSONObject().put("action", "root_request")))
        assertEquals(request.toString(), "ok", request.getString("status"))
        assertEquals(0, request.getJSONObject("data").getInt("effective_uid"))
        assertTrue(request.getJSONObject("data").getString("stdout").contains("uid=0"))
        assertTrue(request.getJSONObject("data").getBoolean("cleanup_verified"))
        return tool
    }
    private suspend fun run(tool: AndroidTool, command: String, directory: File, timeout: Int = 5, limit: Int = 16000): JSONObject =
        JSONObject(tool.execute(JSONObject().put("action", "root_exec").put("params", JSONObject().put("command", command)
            .put("timeout_seconds", timeout).put("output_limit", limit)), directory))

    @Test fun actualAppCanObtainAndUseHostRootWithoutChangingPRootIdentity() = runBlocking {
        val tool = authorizedTool()
        val root = File(app.cacheDir, "root-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val result = run(tool, "/system/bin/id\npwd\nprintf verified > proof.txt", root)
            assertEquals(result.toString(), "ok", result.getString("status"))
            assertEquals("verified", File(root, "proof.txt").readText())
            assertTrue(result.toString(), result.getJSONObject("data").getString("stdout").lineSequence().any { it.trim().endsWith(root.name) })
            assertTrue(result.getJSONObject("data").getBoolean("current_command_verified"))
            assertTrue(android.os.Process.myUid() != 0)
            val refreshed = AndroidTool(app).rootStatus()
            assertEquals("confirmed", refreshed.getString("device_root"))
            assertEquals("granted", refreshed.getString("app_authorization"))
            assertFalse(refreshed.getBoolean("current_command_verified"))
            val failure = run(tool, "printf deliberate-error >&2\nexit 7", root)
            assertEquals(failure.toString(), "command_failed", failure.getString("status"))
            assertEquals(7, failure.getJSONObject("data").getInt("exit_code"))
            assertTrue(failure.getJSONObject("data").getString("stderr").contains("deliberate-error"))
        } finally { root.deleteRecursively() }
    }

    @Test fun timeoutCancellationAndOutputLimitStopManagedRootWork() = runBlocking {
        val tool = authorizedTool()
        val root = File(app.cacheDir, "root-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val timeout = run(tool, "sleep 3\nprintf late > timeout-leak.txt", root, timeout = 1)
            assertEquals(timeout.toString(), "timeout", timeout.getString("status"))
            assertTrue(timeout.getJSONObject("data").getBoolean("cleanup_verified"))
            val job = launch(Dispatchers.Default) { run(tool, "printf ready > started.txt\nsleep 3\nprintf late > cancel-leak.txt", root) }
            withTimeout(5000) { while (!File(root, "started.txt").exists()) delay(25) }
            job.cancelAndJoin()
            val output = run(tool, "i=0; while [ \"\$i\" -lt 5000 ]; do printf 0123456789; i=\$((i+1)); done", root, limit = 1000)
            assertEquals(output.toString(), "output_limit", output.getString("status"))
            assertTrue(output.getJSONObject("data").getBoolean("output_truncated"))
            assertTrue(output.getJSONObject("data").getString("stdout").toByteArray().size <= 1000)
            delay(3300)
            assertFalse(File(root, "timeout-leak.txt").exists()); assertFalse(File(root, "cancel-leak.txt").exists())
        } finally { root.deleteRecursively() }
    }
}
