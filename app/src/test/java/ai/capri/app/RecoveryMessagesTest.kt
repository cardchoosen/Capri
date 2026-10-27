package ai.capri.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecoveryMessagesTest {
    private fun call(id: String, name: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", "{}"))
    @Test fun resumePreservesKnownResultsAndMarksMissingResultsWithoutDispatch() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "rules"))
            .put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(call("done", "terminal")).put(call("unknown", "write_file"))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "done").put("content", "HTTP 200"))
        val resumed = RecoveryMessages.forResume(messages)
        assertEquals(4, resumed.length())
        assertEquals("unknown", resumed.getJSONObject(2).getString("tool_call_id"))
        assertTrue(resumed.getJSONObject(2).getString("content").contains("禁止直接重跑"))
        assertEquals("HTTP 200", resumed.getJSONObject(3).getString("content"))
        assertEquals(3, messages.length())
    }
    @Test fun privateProfileResultsAreNotSavedAndContinuationIsExplicit() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "persona private"))
            .put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(call("profile", "profile_search"))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "profile").put("content", "secret archive result"))
        val saved = RecoveryMessages.sanitized(messages)
        assertFalse(saved.toString().contains("secret archive result"))
        assertFalse(saved.toString().contains("persona private"))
        assertEquals("", RecoveryMessages.evidence(saved))
        assertTrue(RecoveryMessages.isContinuation("继续"))
        assertTrue(RecoveryMessages.isContinuation("？"))
        assertFalse(RecoveryMessages.isContinuation("继续创建另一个项目"))
    }
    @Test fun personalMemoryAndCrossChatLookupResultsCannotLeakThroughRecovery() {
        val memoryCall = call("remember", "personal_memory").apply {
            getJSONObject("function").put("arguments", "{\"action\":\"remember\",\"text\":\"private preference\"}")
        }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "persona"))
            .put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(memoryCall).put(call("history", "conversation_history"))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "remember").put("content", "private stored note"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "history").put("content", "another chat secret"))
        val saved = RecoveryMessages.sanitized(messages).toString()
        listOf("private preference", "private stored note", "another chat secret").forEach { assertFalse(saved.contains(it)) }
        assertTrue(ToolPresentation.isInternal("personal_memory"))
        assertTrue(ToolPresentation.isInternal("conversation_history"))
    }
}
