package ai.capri.app.nativebridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootProtocolTest {
    @Test fun escapedOutputsCannotTruncateResultMetadataInAgentTransport() {
        val (out, err) = RootProtocol.boundedOutput("\u0000".repeat(64000), "error".repeat(10000))
        val result = JSONObject().put("stdout", out).put("stderr", err).put("effective_uid", 0).put("cleanup_verified", true).toString()
        assertTrue(result.length < 31000)
        assertTrue(JSONObject(result).getBoolean("cleanup_verified"))
        assertTrue(out.length < 64000)
    }
    @Test fun explicitCatalogAndLimitsDoNotTurnOtherNativeActionsIntoShellCalls() {
        assertNotNull(NativeCatalog.find("root_status")); assertNotNull(NativeCatalog.find("root_request")); assertNotNull(NativeCatalog.find("root_exec"))
        validateParams("root_exec", JSONObject().put("command", "id").put("timeout_seconds", 1))
        assertThrows(NativeFailure::class.java) { validateParams("root_request", JSONObject().put("command", "id")) }
        assertThrows(NativeFailure::class.java) { validateParams("wifi_info", JSONObject().put("command", "id")) }
        listOf(0, 121, 1.5, "30").forEach { value -> assertThrows(NativeFailure::class.java) {
            RootProtocol.request(JSONObject().put("command", "id").put("timeout_seconds", value))
        } }
        assertThrows(NativeFailure::class.java) { RootProtocol.request(JSONObject().put("command", "id\u0000whoami")) }
        assertThrows(NativeFailure::class.java) { RootProtocol.request(JSONObject().put("command", "id").put("output_limit", 64001)) }
        assertEquals(30, RootProtocol.request(JSONObject().put("command", "id")).timeoutSeconds)
    }

    @Test fun untrustedCommandIsNeverInterpolatedIntoPrivilegedSupervisorOrLauncher() {
        val command = "printf '%s' '\$(id)'; echo `whoami`; echo \"quote'\\n\""
        assertEquals(command, RootProtocol.request(JSONObject().put("command", command)).command)
        val directory = File("/data/user/0/test/cache/with'quote")
        val launch = RootProtocol.launch("/system/bin/su", directory)
        assertEquals(listOf("/system/bin/su", "-c"), launch.take(2))
        assertFalse(launch.joinToString().contains(command))
        val supervisor = RootProtocol.supervisor(directory, File("/workspace"), 45)
        assertFalse(supervisor.contains(command))
        assertTrue(supervisor.contains("[ \"\$uid\" = 0 ]"))
        assertTrue(supervisor.indexOf("while [ ! -s release ]") < supervisor.indexOf("/system/bin/sh '/data"))
        assertTrue(supervisor.contains("kill -KILL -\$\$"))
        val cleanup = RootProtocol.cleanup(directory)
        assertTrue(cleanup.contains("\"\$1\" = \"\$started\""))
        assertTrue(cleanup.contains("kill -0"))
    }
}
