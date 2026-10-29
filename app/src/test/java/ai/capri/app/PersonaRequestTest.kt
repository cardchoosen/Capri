package ai.capri.app

import ai.capri.app.model.ContextTokens
import ai.capri.app.model.ModelWire
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersonaRequestTest {
    private fun history() = JSONArray("""[
        {"role":"system","content":"Core rules"},
        {"role":"user","content":"Read status"},
        {"role":"assistant","tool_calls":[{"id":"r1","type":"function","function":{"name":"android","arguments":"{\"action\":\"root_status\"}"}}]},
        {"role":"tool","tool_call_id":"r1","content":"{\"current_command_verified\":false}"}
    ]""")

    @Test fun voiceControlDoesNotMutateHistoryOrDisruptToolResultsAndIsIncludedInBudget() {
        val canonical = history(); val original = canonical.toString()
        val request = PersonaRuntime.requestMessages(canonical, SessionKind.PERSONAL)
        assertEquals(original, canonical.toString())
        assertEquals(4, canonical.length()); assertEquals(5, request.length())
        assertEquals(canonical.getJSONObject(2).toString(), request.getJSONObject(2).toString())
        assertEquals("r1", request.getJSONObject(3).getString("tool_call_id"))
        assertEquals("system", request.getJSONObject(4).getString("role"))
        assertEquals(PersonaRuntime.requestOverhead(SessionKind.PERSONAL),
            ContextTokens.estimate(request, JSONArray()) - ContextTokens.estimate(canonical, JSONArray()))
        assertEquals(5, PersonaRuntime.requestMessages(canonical, SessionKind.PERSONAL).length())
        assertSame(canonical, PersonaRuntime.requestMessages(canonical, SessionKind.TASK))
        assertEquals(0L, PersonaRuntime.requestOverhead(SessionKind.TASK))
    }

    @Test fun supportedProtocolsRetainSystemVoiceAndPairedToolEvidence() {
        for (protocol in listOf("CHAT", "RESPONSES", "ANTHROPIC", "GEMINI")) {
            val c = ModelConfig(model = "test-model", protocol = protocol)
            val messages = PersonaRuntime.requestMessages(history(), SessionKind.PERSONAL)
            val body = ModelWire.request(c, messages, JSONArray(), 1024)
            val raw = body.toString()
            assertTrue(protocol, raw.contains("current_command_verified"))
            assertTrue(protocol, raw.contains("19岁的Capri"))
            assertTrue(protocol, raw.contains("root_status"))
            when (protocol) {
                "ANTHROPIC" -> assertTrue(body.getString("system").contains("Core rules"))
                "GEMINI" -> assertTrue(body.getJSONObject("systemInstruction").toString().contains("Core rules"))
            }
            assertEquals(4, history().length())
        }
    }
}
