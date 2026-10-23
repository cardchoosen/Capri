package ai.capri.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CapabilityContractTest {
    private fun valid() = JSONObject().put("language", "cpp").put("outputs", JSONArray(listOf("gif"))).put("allow_helpers", false)
    @Test fun requiresExplicitLanguageBoundaryAndRejectsInjectedArguments() {
        assertFalse(CapabilityContract.validate(valid()).getBoolean("allow_helpers"))
        val invalid = listOf(valid().apply { remove("allow_helpers") }, valid().put("allow_helpers", "false"),
            valid().put("command", "anything"), valid().put("language", "cpp; echo"),
            valid().put("outputs", JSONArray(listOf("gif", "gif"))), valid().put("outputs", JSONArray(listOf("unknown"))))
        invalid.forEach { args ->
            try { CapabilityContract.validate(args); fail("Accepted $args") } catch (_: IllegalArgumentException) { }
        }
    }
}
