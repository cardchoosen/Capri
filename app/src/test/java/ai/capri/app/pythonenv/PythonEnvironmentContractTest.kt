package ai.capri.app.pythonenv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PythonEnvironmentContractTest {
    private fun request(action: String, vararg packages: String) = JSONObject().put("action", action).put("packages", JSONArray(packages.toList()))
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid request") } catch (_: IllegalArgumentException) { }
    }
    @Test fun packageSpecsAreNamesAndVersionsNotShellOptionsOrCommands() {
        val valid = PythonEnvironmentContract.validate(request("install", "packaging==24.2", "requests[socks]>=2.31,<3"))
        assertEquals(2, valid.second.size)
        rejects { PythonEnvironmentContract.validate(request("install", "-r requirements.txt")) }
        rejects { PythonEnvironmentContract.validate(request("install", "x; touch /tmp/unsafe")) }
        rejects { PythonEnvironmentContract.validate(request("install", "https://example.com/file.whl")) }
        rejects { PythonEnvironmentContract.validate(request("uninstall", "packaging==24.2")) }
        rejects { PythonEnvironmentContract.validate(request("uninstall", "PIP")) }
    }
    @Test fun actionFieldsTypesAndWaitBudgetAreStrict() {
        rejects { PythonEnvironmentContract.validate(request("unknown", "packaging")) }
        rejects { PythonEnvironmentContract.validate(request("list", "packaging")) }
        rejects { PythonEnvironmentContract.validate(JSONObject().put("action", "install").put("packages", "packaging")) }
        rejects { PythonEnvironmentContract.validate(JSONObject().put("action", "status").put("chat_id", "other")) }
        rejects { PythonEnvironmentContract.validate(JSONObject().put("action", "status").put("wait_ms", 1.5)) }
        rejects { PythonEnvironmentContract.validate(JSONObject().put("action", "status").put("wait_ms", 10001)) }
        assertEquals("status", PythonEnvironmentContract.validate(JSONObject().put("action", "status").put("wait_ms", 10000)).first)
    }
    @Test fun managedCommandsUseCurrentVenvAndExposeJobLifecycle() {
        val command = PythonEnvironmentContract.command("install", listOf("packaging==24.2"))
        assertTrue(command.contains("/root/.venv/bin/python3 -I /opt/capri/bootstrap/python_packages.py"))
        assertFalse(command.contains("--user"))
        assertFalse(command.contains("--target"))
        assertTrue(PythonEnvironmentContract.command("check", emptyList()).endsWith(" check"))
        val parameters = PythonEnvironmentContract.specification().getJSONObject("function").getJSONObject("parameters")
        assertEquals("array", parameters.getJSONObject("properties").getJSONObject("packages").getString("type"))
    }
}
