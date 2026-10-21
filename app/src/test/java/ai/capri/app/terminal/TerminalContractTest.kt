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
}
