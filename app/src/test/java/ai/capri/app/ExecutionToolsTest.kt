package ai.capri.app

import ai.capri.app.terminal.TerminalContract
import ai.capri.app.terminal.TerminalOutput
import ai.capri.app.terminal.TerminalResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExecutionToolsTest {
    @Test fun englishContractsShareRequirementsAndCloseActionSpecificFields() {
        val specs = JSONObject().put("terminal", TerminalContract.specification())
            .put("python_run", ShortTaskContract.specification()).put("environment", CapabilityContract.specification())
        assertFalse(specs.toString().any { it in '\u4e00'..'\u9fff' })
        val requirement = CapabilityContract.requirementsSchema().toString()
        assertEquals(requirement, ShortTaskContract.specification().getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").getJSONObject("requirements").toString())
        val environment = CapabilityContract.specification().getJSONObject("function").getJSONObject("parameters")
        val branches = environment.getJSONArray("oneOf")
        for (i in 0 until branches.length()) {
            val branch = branches.getJSONObject(i)
            assertFalse(branch.getBoolean("additionalProperties"))
            val action = branch.getJSONObject("properties").getJSONObject("action").getJSONArray("enum").getString(0)
            val props = branch.getJSONObject("properties")
            if (action == "record") assertTrue(branch.getJSONArray("required").toString().contains("source_receipt"))
            if (action != "verify") assertFalse(props.has("command"))
            if (action != "prepare") assertFalse(props.has("from_project"))
        }
        assertTrue(TerminalContract.specification().toString().length < 8500)
    }
    @Test fun malformedRequestsFailBeforeExecutionAndLegacyAliasesStillValidate() {
        for (raw in listOf("""{"action":true}""", """{"action":"prepare","requirements":null}""",
            """{"action":"inspect","from_project":true}""", """{"action":"verify","command":["true"],"timeout_seconds":1.5}""",
            """{"action":"lookup","scope":false}""", """{"action":"lookup","offset":9223372036854775807}""",
            """{"action":"record","key":"a","title":"b","conclusion":"c","source_receipt":"d","excerpt":"e","validity":"volatile","ttl_seconds":301}""")) {
            try { CapabilityContract.validate(JSONObject(raw)); fail("Accepted $raw") }
            catch (e: IllegalArgumentException) { assertFalse(e.message.orEmpty().any { it in '\u4e00'..'\u9fff' }) }
        }
        assertEquals("inspect", CapabilityContract.validate(JSONObject()).getString("action"))
        assertEquals("print(42)", ShortTaskContract.validate(JSONObject().put("code", "print(42)")).getString("code"))
        val id = "12345678-1234-1234-1234-123456789abc"
        val params = JSONObject().put("terminal_id", id).put("command", "true").put("wait_ms", 0)
        assertEquals(TerminalContract.validate(JSONObject(params.toString()).put("action", "run")).second.toString(),
            TerminalContract.validate(JSONObject().put("action", "run").put("params", params)).second.toString())
        try { ShortTaskContract.validate(JSONObject().put("code", "print('bad\u0000')")); fail("NUL accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun escapedTerminalOutputContinuesByOriginalByteOffsetsWithoutLoss() {
        val original = "😀\u2028\u0000\"\\\n".repeat(8000)
        val bytes = original.toByteArray(); var offset = 0; val recovered = StringBuilder(); var pages = 0
        while (offset < bytes.size) {
            val end = minOf(offset + 32000, bytes.size)
            val chunk = TerminalOutput.bounded(bytes.copyOfRange(offset, end), end == bytes.size)
            assertTrue(chunk.consumed > 0); assertTrue(JSONObject.quote(chunk.text).length <= 24000)
            val raw = TerminalResult.encode(JSONObject().put("status", "ok").put("data", JSONObject().put("output", chunk.text).put("next_offset", offset + chunk.consumed)))
            assertTrue(raw.length <= 36000)
            recovered.append(JSONObject(raw).getJSONObject("data").getString("output")); offset += chunk.consumed; pages++
        }
        assertTrue(pages > 1); assertEquals(original, recovered.toString())
        assertEquals("normal output", TerminalOutput.bounded("normal output".toByteArray(), true).text)
        val malformed = TerminalOutput.bounded(byteArrayOf(0x80.toByte()), true)
        assertEquals(1, malformed.consumed)
    }
    @Test fun pagedArchiveTextSurvivesJsonEscapingAndUnicodeBoundaries() {
        val original = "😀\u2028\"\\\n".repeat(10000); var offset = 0; val recovered = StringBuilder()
        while (offset < original.length) {
            val page = ExecutionResult.textPage(original, offset)
            assertTrue(page.isNotEmpty()); assertTrue(JSONObject.quote(page).length <= 24000)
            assertFalse(page.last().isHighSurrogate()); recovered.append(page); offset += page.length
        }
        assertEquals(original, recovered.toString())
    }
    @Test fun largeEnvironmentAndTerminalHistoryKeepOutcomeAndExplicitOmission() {
        val checks = JSONObject()
        repeat(100) { checks.put("command:$it", JSONObject().put("present", it != 2).put("output", "\"".repeat(8000))) }
        val compact = JSONObject(ExecutionResult.environment(JSONObject().put("status", "ok").put("checks", checks).put("requirements_satisfied", false)))
        assertTrue(compact.toString().length <= 33000); assertFalse(compact.getBoolean("requirements_satisfied"))
        assertFalse(compact.getJSONObject("checks").getJSONObject("command:2").getBoolean("present")); assertTrue(compact.getBoolean("details_omitted"))
        val list = JSONArray()
        repeat(32) { list.put(JSONObject().put("terminal_id", "$it").put("title", "Terminal").put("state", "closed").put("commands", JSONArray().put(JSONObject().put("preview", "x".repeat(3000))))) }
        val response = JSONObject(TerminalResult.encode(JSONObject().put("status", "ok").put("data", JSONObject().put("terminals", list))))
        assertEquals(32, response.getJSONObject("data").getJSONArray("terminals").length()); assertTrue(response.getJSONObject("data").getBoolean("history_omitted"))
        val failure = ExecutionResult.annotate(JSONObject().put("status", "execution_failed").put("phase", "execute").put("exit_code", 7))
        assertEquals("execution_failed", failure.getJSONObject("error").getString("code")); assertEquals(7, failure.getInt("exit_code"))
        assertTrue(ExecutionResult.message(RuntimeBusyException("环境忙")).contains("busy"))
    }
}
