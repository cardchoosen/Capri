package ai.capri.app

import ai.capri.app.model.ModelCapacity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersonaRequestTest {
    private fun history() = JSONArray("""[
        {"role":"system","content":"EXECUTION_ONLY_RULES"},
        {"role":"user","content":"Read status"},
        {"role":"assistant","tool_calls":[{"id":"r1","type":"function","function":{"name":"android","arguments":"{\"action\":\"root_status\"}"}}]},
        {"role":"tool","tool_call_id":"r1","content":"{\"current_command_verified\":false,\"root_invocations\":0}"},
        {"role":"assistant","content":"Three cached checks were reused; no Root execution occurred. [report](https://example.com/report)"}
    ]""")
    private val budget get() = ModelCapacity(32768, 4096, "test").budget()
    private fun response(text: String = "最终回复", reason: String = "stop", calls: JSONArray? = null) =
        JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", reason).put("message",
            JSONObject().put("content", text).apply { if (calls != null) put("tool_calls", calls) })))

    @Test fun characterStartsOnlyTheTextOnlyFinalRequestForEveryProtocol() {
        val execution = history(); val state = FinalReply.mark(execution, "这回用了root吗？", execution.getJSONObject(4).getString("content"))
        val original = execution.toString()
        val input = FinalReply.messages("CHARACTER_CORE", SessionKind.PERSONAL, "now", "", state, execution, budget)
        assertTrue(input.getJSONObject(0).getString("content").startsWith("CHARACTER_CORE"))
        assertTrue(input.getJSONObject(0).getString("content").contains("19岁"))
        assertFalse(input.toString().contains("EXECUTION_ONLY_RULES"))
        assertFalse(input.toString().contains(TurnCompletion.guidance))
        assertEquals(original, execution.toString())
        for (protocol in listOf("CHAT", "RESPONSES", "ANTHROPIC", "GEMINI")) {
            val body = FinalReply.body(ModelConfig(model = "test", protocol = protocol), input, 1024)
            assertFalse(protocol, body.has("tools"))
            assertTrue(protocol, body.toString().contains("current_command_verified"))
            assertTrue(protocol, body.toString().replace("\\", "").contains("https://example.com/report"))
            assertFalse(protocol, body.toString().contains("tool_calls"))
        }
    }

    @Test fun interruptedFinalIsRecoverableWithoutReplayingToolsAndDraftNeverBecomesFinalHistory() {
        val execution = history(); val draft = execution.getJSONObject(4).getString("content")
        val state = FinalReply.mark(execution, "question", draft)
        val checkpoint = RecoveryMessages.sanitized(execution)
        assertEquals(draft, FinalReply.pending(checkpoint)!!.getString("draft"))
        assertEquals("question", FinalReply.pending(checkpoint)!!.getString("request"))
        assertThrows(IllegalStateException::class.java) { FinalReply.text(response("", calls = JSONArray().put(JSONObject().put("id", "never-execute")))) }
        assertEquals(state.toString(), FinalReply.pending(execution)!!.toString())
        assertThrows(IllegalStateException::class.java) { FinalReply.text(response("half", "length")) }
        FinalReply.complete(execution, state, FinalReply.text(response("结果还有效，这回没叫Root。")))
        assertNull(FinalReply.pending(execution))
        assertEquals(5, execution.length())
        assertEquals("r1", execution.getJSONObject(3).getString("tool_call_id"))
        assertEquals("结果还有效，这回没叫Root。", execution.getJSONObject(4).getString("content"))
        assertFalse(execution.toString().contains("Three cached checks"))
    }

    @Test fun evidenceShrinksToBudgetWithoutSilentlyTruncatingTheAnswerOrRequest() {
        val execution = history(); execution.getJSONObject(3).put("content", "证据".repeat(10000))
        val state = FinalReply.mark(execution, "request-kept", execution.getJSONObject(4).getString("content"))
        val small = ModelCapacity(8192, 1024, "test").budget()
        val input = FinalReply.messages("core", SessionKind.TASK, "now", "", state, execution, small)
        assertTrue(small.fits(ai.capri.app.model.ContextTokens.estimate(input, JSONArray())))
        val packet = JSONObject(input.getJSONObject(2).getString("content").substringAfter("\n"))
        assertEquals(state.getString("draft"), packet.getString("internal_answer"))
        assertEquals("request-kept", input.getJSONObject(1).getString("content"))
        assertTrue(packet.getJSONArray("tool_evidence").getJSONObject(0).getBoolean("truncated"))
        assertThrows(ai.capri.app.knowledge.ContextBudgetFailure::class.java) {
            FinalReply.messages("core".repeat(10000), SessionKind.PERSONAL, "now", "", state, execution, small)
        }
    }

    @Test fun aFreshQuestionCanDiscardPendingSpeechWithoutRemovingActualToolEvidence() {
        val execution = history(); val state = FinalReply.mark(execution, "old question", execution.getJSONObject(4).getString("content"))
        FinalReply.discardDraft(execution, state)
        assertNull(FinalReply.pending(execution)); assertEquals(4, execution.length())
        assertEquals("tool", execution.getJSONObject(3).getString("role"))
    }
    @Test fun currentTurnWithoutToolsDoesNotResurfaceOldToolResults() {
        val execution = history()
        val state = FinalReply.mark(execution, "我回来了", execution.getJSONObject(4).getString("content"), emptySet())
        val input = FinalReply.messages("core", SessionKind.PERSONAL, "now", "", state, execution, budget)
        val packet = JSONObject(input.getJSONObject(2).getString("content").substringAfter("\n"))
        assertEquals(0, packet.getJSONArray("tool_evidence").length())
    }

}
