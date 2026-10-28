package ai.capri.app.nativebridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RootInspectionProtocolTest {
    private fun request(query: String) = JSONObject("""{"queries":[$query]}""")
    @Test fun rejectsCommandsTraversalDuplicateIDsAndUnboundedDynamicCaching() {
        listOf(
            """{"id":"x","type":"uname","command":"id"}""",
            """{"id":"x","type":"read_file","value":"/proc/../data/secrets"}""",
            """{"id":"x","type":"read_file","value":"/proc/self/mem"}""",
            """{"id":"x","type":"getprop","value":"ro.serialno"}""",
            """{"id":"x","type":"kernel_log","ttl_seconds":600}""",
            """{"id":"x","type":"command_exists","value":"-p"}""",
            """{"id":"x","type":"uname"},{"id":"x","type":"uname"}"""
        ).forEach { json -> assertThrows(Exception::class.java) { RootInspectionProtocol.validate(request(json)) } }
        val dynamic = RootInspectionProtocol.validate(request("""{"id":"x","type":"read_file","value":"/proc/version","validity":"stable"}""")).queries.single()
        assertEquals("volatile", dynamic.validity); assertEquals(5, dynamic.ttl)
    }
    @Test fun literalValuesAreQuotedAndFramesKeepIndependentFailuresAndRejectIncompleteOutput() {
        val queries = RootInspectionProtocol.validate(request("""{"id":"a","type":"read_file","value":"/sys/test'file","contains":"$(id);x"},{"id":"b","type":"uname"}""")).queries
        val nonce = "12345678-1234-1234-1234-123456789012"
        val script = RootInspectionProtocol.script(queries, nonce)
        assertTrue(script.contains(RootProtocol.quote("/sys/test'file")))
        assertTrue(script.contains(RootProtocol.quote("$(id);x")))
        assertTrue(script.startsWith("set -o pipefail\n")); assertTrue(script.endsWith("\nexit 0\n"))
        val output = "\n${nonce}_BEGIN_a\npermission denied\n${nonce}_END_a_1\n\n${nonce}_BEGIN_b\nLinux\n${nonce}_END_b_0\n"
        val parsed = RootInspectionProtocol.parse(output, queries, nonce)
        assertEquals("failed", parsed.getValue("a").getString("status"))
        assertEquals("ok", parsed.getValue("b").getString("status"))
        assertEquals("Linux", parsed.getValue("b").getString("output"))
        assertFalse(RootInspectionProtocol.parse(output.substringBefore("${nonce}_END_b"), queries, nonce).containsKey("b"))
    }
    @Test fun cachingIgnoresDisplayIDButRespectsSamplingAndFreshnessPolicy() {
        fun query(id: String, extra: String = "") = RootInspectionProtocol.validate(request("""{"id":"$id","type":"uname"$extra}""")).queries.single()
        assertEquals(query("one").key, query("two").key)
        assertNotEquals(query("one").key, query("one", ",\"ttl_seconds\":30").key)
        assertNotEquals(query("one").key, query("one", ",\"limit_bytes\":100").key)
    }
}
