package ai.capri.app.terminal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerminalContractTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun request(action: String, params: JSONObject = JSONObject()) = JSONObject().put("action", action).put("params", params)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid terminal request") } catch (_: IllegalArgumentException) { }
    }
    @Test fun closedActionsAndChatOwnedIdsPreventPathOrPidControl() {
        assertEquals(TerminalContract.actions.toSet().size, TerminalContract.actions.size)
        assertEquals("terminal", TerminalContract.specification().getJSONObject("function").getString("name"))
        rejects { TerminalContract.validate(request("kill_pid", JSONObject().put("pid", 123))) }
        rejects { TerminalContract.validate(request("read", JSONObject().put("terminal_id", "../../other"))) }
        rejects { TerminalContract.validate(request("run", JSONObject().put("terminal_id", id).put("command", "pwd").put("chat_id", "other"))) }
    }
    @Test fun timeoutReadAndInputLimitsAreValidatedBeforeDispatch() {
        val params = JSONObject().put("terminal_id", id).put("command", "pwd").put("timeout_seconds", 86400)
        assertEquals("run", TerminalContract.validate(request("run", params)).first)
        rejects { TerminalContract.validate(request("run", JSONObject(params.toString()).put("timeout_seconds", 0))) }
        rejects { TerminalContract.validate(request("run", JSONObject(params.toString()).put("timeout_seconds", 1.5))) }
        rejects { TerminalContract.validate(request("read", JSONObject().put("terminal_id", id).put("offset", -1))) }
        rejects { TerminalContract.validate(request("input", JSONObject().put("terminal_id", id).put("command_id", id).put("text", "羯".repeat(2000)))) }
    }
    @Test fun invalidObjectTypesAndMissingFieldsAreRejected() {
        rejects { TerminalContract.validate(JSONObject().put("action", "list").put("params", "not an object")) }
        rejects { TerminalContract.validate(request("cancel", JSONObject().put("terminal_id", id))) }
        rejects { TerminalContract.validate(request("run", JSONObject().put("terminal_id", id).put("command", "echo\u0000bad"))) }
        assertEquals("list", TerminalContract.validate(JSONObject().put("action", "list")).first)
    }
    @Test fun runAndReadExposeTypedWaitBudgetsAndRejectOutOfRangeValues() {
        val run = JSONObject().put("terminal_id", id).put("command", "sleep 3").put("wait_ms", 10000)
        assertEquals("run", TerminalContract.validate(request("run", run)).first)
        rejects { TerminalContract.validate(request("run", JSONObject(run.toString()).put("wait_ms", 10001))) }
        val read = JSONObject().put("terminal_id", id).put("wait_ms", 20000)
        assertEquals("read", TerminalContract.validate(request("read", read)).first)
        rejects { TerminalContract.validate(request("read", JSONObject(read.toString()).put("wait_ms", 20001))) }
        val branches = TerminalContract.specification().getJSONObject("function").getJSONObject("parameters").getJSONArray("oneOf")
        fun schema(action: String): JSONObject = (0 until branches.length()).map { branches.getJSONObject(it) }
            .first { it.getJSONObject("properties").getJSONObject("action").getJSONArray("enum").getString(0) == action }
            .getJSONObject("properties").getJSONObject("params")
        assertEquals(10000, schema("run").getJSONObject("properties").getJSONObject("wait_ms").getInt("maximum"))
        assertEquals(20000, schema("read").getJSONObject("properties").getJSONObject("wait_ms").getInt("maximum"))
        assertTrue(schema("run").getJSONArray("required").toString().contains("command"))
        assertFalse(schema("read").getJSONObject("properties").has("command"))
        assertTrue(schema("run").getJSONObject("properties").getJSONObject("terminal_id").has("pattern"))

    }

}
