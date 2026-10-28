package ai.capri.app

import ai.capri.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelInfoTest {
    private val active = ModelConfig("https://private.example/path-sensitive", "deepseek-flash", "sk-FAKE-DO-NOT-RETURN", "CHAT", "private-account", 1048576, 393216, "models_api", "deepseek", "DeepSeek-V4.1-Flash")
    private val budget = ModelCapacity(1048576, 393216, "models_api").budget()
    @Test fun currentReportsTaskSnapshotAndPendingChoiceWithoutLeakingCredentials() {
        val changed = active.copy(model = "other-model", modelName = "Other model", key = "sk-OTHER-SECRET", providerId = "openai")
        val result = ModelInfoTool.execute(active, changed, emptyList(), JSONObject(), budget)
        val info = JSONObject(result)
        assertEquals("Capri / 摩羯", info.getString("persona"))
        assertEquals("deepseek-flash", info.getJSONObject("active_model").getString("model_id"))
        assertEquals("DeepSeek-V4.1-Flash", info.getJSONObject("active_model").getString("display_name"))
        assertTrue(info.getBoolean("selection_changed_for_next_turn"))
        assertEquals("other-model", info.getJSONObject("next_turn_model").getString("model_id"))
        for (secret in listOf(active.key, changed.key, active.endpoint, active.accountId)) assertFalse(result.contains(secret))
        assertEquals("unknown_not_benchmarked", info.getJSONObject("capability_boundary").getString("compute_grade"))
        val current = JSONObject(ModelInfoTool.execute(active, active, emptyList(), JSONObject(), budget))
        assertFalse(current.getBoolean("selection_changed_for_next_turn")); assertTrue(current.isNull("next_turn_model"))
        val deleted = JSONObject(ModelInfoTool.execute(active, ModelConfig(), emptyList(), JSONObject(), budget))
        assertTrue(deleted.getBoolean("next_turn_requires_selection"))
    }
    @Test fun configuredCatalogIsPaginatedAndUnknownCapabilitiesStayUnknown() {
        val account = ModelAccount(provider = "deepseek", name = "私有 sk-LABEL-SECRET", endpoint = active.endpoint, key = active.key, protocol = ModelProtocol.CHAT,
            models = listOf(ModelOption("first", context = 1048576, source = "models_registry"), ModelOption("second")))
        val args = JSONObject().put("action", "list").put("limit", 1)
        val first = JSONObject(ModelInfoTool.execute(active, active, listOf(account), args, budget))
        assertEquals(2, first.getInt("total")); assertTrue(first.getBoolean("has_more"))
        assertTrue(first.getJSONArray("models").getJSONObject(0).getBoolean("reference_only"))
        assertFalse(first.toString().contains("sk-LABEL-SECRET"))
        args.put("offset", 1)
        val second = JSONObject(ModelInfoTool.execute(active, active, listOf(account), args, budget))
        assertFalse(second.getBoolean("has_more"))
        val model = second.getJSONArray("models").getJSONObject(0)
        assertTrue(model.isNull("nominal_context_tokens")); assertEquals("unknown", model.getString("context_source"))
        args.put("query", "first").put("offset", 0)
        assertEquals(1, JSONObject(ModelInfoTool.execute(active, active, listOf(account), args, budget)).getInt("total"))
        for (invalid in listOf(JSONObject().put("action", "switch"), JSONObject().put("action", "current").put("offset", 0),
            JSONObject().put("action", "list").put("limit", 0), JSONObject().put("action", "list").put("offset", 1.5), JSONObject().put("key", "secret"))) {
            assertTrue(runCatching { ModelInfoTool.execute(active, active, listOf(account), invalid, budget) }.isFailure)
        }
    }
    @Test fun internalResultsAreNotReplayedFromCheckpoints() {
        val messages = JSONArray().put(JSONObject().put("role", "assistant").put("_capri_wire", JSONObject().put("raw", "OLD-MODEL-INFO"))
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "info-1").put("function", JSONObject().put("name", "model_info").put("arguments", "{}")))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "info-1").put("content", "OLD-MODEL-INFO"))
        val saved = RecoveryMessages.sanitized(messages)
        assertFalse(saved.toString().contains("OLD-MODEL-INFO"))
        assertEquals("info-1", saved.getJSONObject(1).getString("tool_call_id"))
        assertTrue(ModelInfoTool.guidance.contains("先调用 model_info"))
        assertTrue(ModelInfoTool.specification().toString().contains("current"))
        assertFalse(ModelInfoTool.specification().getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").getJSONObject("action").getJSONArray("enum").toString().contains("switch"))
    }
}
