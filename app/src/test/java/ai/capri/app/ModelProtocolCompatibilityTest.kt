package ai.capri.app

import ai.capri.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelProtocolCompatibilityTest {
    @Test fun deepSeekReasoningUsesProviderFieldsAndSeparatesDisabledToolHistory() {
        val config = ModelConfig(endpoint = "https://api.deepseek.com/v1", model = "deepseek-flash", key = "fixture")
        fun body(c: ModelConfig) = ModelWire.request(c, JSONArray(), JSONArray(), 1024)
        val disabled = config.copy(reasoningEffort = "none")
        val maximum = config.copy(reasoningEffort = "max")
        assertFalse(body(config).has("reasoning_effort"))
        assertEquals("none", body(disabled).getString("reasoning_effort"))
        assertEquals("max", body(maximum).getString("reasoning_effort"))
        assertEquals("max", body(maximum.copy(protocol = "RESPONSES")).getJSONObject("reasoning").getString("effort"))
        val anthropic = maximum.copy(protocol = "ANTHROPIC", endpoint = "https://api.deepseek.com/anthropic/v1")
        assertEquals("max", body(anthropic).getJSONObject("output_config").getString("effort"))
        assertEquals("disabled", body(anthropic.copy(reasoningEffort = "none")).getJSONObject("thinking").getString("type"))
        assertFalse(body(maximum.copy(endpoint = "https://example.org/v1")).has("reasoning_effort"))
        assertEquals(ModelWire.scope(config), ModelWire.scope(maximum))
        assertNotEquals(ModelWire.scope(config), ModelWire.scope(disabled))
    }

    @Test fun responsesRoundTripPreservesEncryptedReasoningAndToolIds() {
        val c = ModelConfig("https://example.com/v1", "test", "key", "RESPONSES")
        val raw = JSONObject("""{"status":"completed","output":[{"type":"reasoning","encrypted_content":"signed"},{"type":"function_call","id":"fc1","call_id":"c1","name":"lookup","arguments":"{}"}],"usage":{"input_tokens":100,"output_tokens":10}}""")
        val normalized = ModelWire.response(c, raw)
        assertEquals(100, normalized.getJSONObject("usage").getInt("prompt_tokens"))
        val message = normalized.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val history = JSONArray().put(JSONObject().put("role", "system").put("content", "core")).put(message)
            .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "done"))
        val request = ModelWire.request(c, history, JSONArray(), 1000)
        assertFalse(request.getBoolean("store"))
        val input = request.getJSONArray("input")
        assertEquals("signed", input.getJSONObject(1).getString("encrypted_content"))
        assertEquals("c1", input.getJSONObject(2).getString("call_id"))
        assertEquals("function_call_output", input.getJSONObject(3).getString("type"))
        val portable = ModelWire.portable(history)
        assertFalse(portable.toString().contains("signed"))
        assertTrue(portable.toString().contains("done"))
        assertTrue(portable.toString().contains("先核验"))
        assertFalse(ModelWire.request(c.copy(key = "different"), history, JSONArray(), 1000).toString().contains("signed"))
    }
    @Test fun anthropicParallelToolsKeepThinkingAndGroupedResults() {
        val c = ModelConfig("https://example.com/v1", "claude", "key", "ANTHROPIC")
        val raw = JSONObject("""{"content":[{"type":"thinking","thinking":"private","signature":"abc"},{"type":"tool_use","id":"a","name":"lookup","input":{}},{"type":"tool_use","id":"b","name":"lookup","input":{}}],"stop_reason":"tool_use","usage":{"input_tokens":10,"cache_read_input_tokens":20,"cache_creation_input_tokens":5}}""")
        val reply = ModelWire.response(c, raw)
        assertEquals(35, reply.getJSONObject("usage").getInt("prompt_tokens"))
        val message = reply.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        assertEquals("", message.getString("content"))
        val history = JSONArray().put(JSONObject().put("role", "system").put("content", "core")).put(message)
        for (id in listOf("a", "b")) history.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", "result"))
        val request = ModelWire.request(c, history, JSONArray(), 1000)
        assertEquals("core", request.getString("system"))
        assertEquals(2, request.getJSONArray("messages").length())
        assertEquals("abc", request.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("signature"))
        assertEquals(2, request.getJSONArray("messages").getJSONObject(1).getJSONArray("content").length())
    }
    @Test fun geminiSignaturesRoundTripAndUnsignedCallIdsAreNotInventedOnWire() {
        val c = ModelConfig("https://example.com/v1beta", "gemini", "key", "GEMINI")
        val raw = JSONObject("""{"candidates":[{"content":{"parts":[{"thought":true,"text":"private"},{"functionCall":{"name":"lookup","args":{}},"thoughtSignature":"signed"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":50}}""")
        val message = ModelWire.response(c, raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        assertEquals("", message.getString("content"))
        val id = message.getJSONArray("tool_calls").getJSONObject(0).getString("id")
        val nextId = ModelWire.response(c, raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0).getString("id")
        assertNotEquals(id, nextId)
        val history = JSONArray().put(JSONObject().put("role", "system").put("content", "core")).put(message)
            .put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", "result"))
        val request = ModelWire.request(c, history, JSONArray(), 1000)
        assertEquals("signed", request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getString("thoughtSignature"))
        val result = request.getJSONArray("contents").getJSONObject(1).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("lookup", result.getString("name")); assertFalse(result.has("id"))
        assertEquals("/v1beta/models/gemini:generateContent", ModelWire.url(c).encodedPath)
        assertNull(ModelWire.url(c.copy(model = "weird?key=x")).query)
    }
    @Test fun chatExtensionsStayWithinScopeAndTruncatedNativeToolsAreMarked() {
        val c = ModelConfig("https://example.com/v1", "test", "key")
        val raw = JSONObject("""{"choices":[{"message":{"role":"assistant","content":"text","reasoning_content":"reason"},"finish_reason":"stop"}]}""")
        val history = JSONArray().put(ModelWire.response(c, raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message"))
        assertTrue(ModelWire.request(c, history, JSONArray(), 1000).getJSONArray("messages").getJSONObject(0).has("reasoning_content"))
        assertFalse(ModelWire.request(c.copy(model = "other"), history, JSONArray(), 1000).getJSONArray("messages").getJSONObject(0).has("reasoning_content"))
        assertFalse(ModelWire.request(c, history, JSONArray(), 1000).toString().contains("_capri_scope"))
        val truncated = ModelWire.response(c.copy(protocol = "RESPONSES"), JSONObject("""{"status":"incomplete","output":[]}"""))
        assertEquals("length", truncated.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        val internal = JSONObject().put("role", "assistant").put("_capri_wire", JSONObject().put("secret", "hidden"))
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "id").put("function", JSONObject().put("name", "profile_search").put("arguments", "{}"))))
        assertFalse(RecoveryMessages.sanitized(JSONArray().put(internal)).toString().contains("hidden"))
    }
    @Test fun datedCatalogOnlyFillsMissingMetadataAtTheKnownEndpoint() {
        val catalog = ModelCatalog("""{"providers":{"openai":{"g":{"context":1048576,"output":65536}}}}""")
        val account = ModelAccount(provider = "openai", name = "fixture", endpoint = "https://api.openai.com/v1", key = "key", protocol = ModelProtocol.RESPONSES)
        val result = catalog.enrich(account, listOf(ModelOption("g"))).single()
        assertEquals(1_048_576, result.context); assertEquals("models_registry", result.source)
        assertEquals("models_registry", account.config(result).contextSource)
        assertEquals(32768, catalog.enrich(account, listOf(ModelOption("g", context = 32768))).single().context)
        assertNull(catalog.enrich(account.copy(endpoint = "https://proxy.example/v1"), listOf(ModelOption("g"))).single().context)
        assertNull(catalog.enrich(account, listOf(ModelOption("unknown"))).single().context)
    }
    @Test fun nativeContextOverflowsAreDistinctFromOrdinaryBadParameters() {
        val claude = ModelContextRejected.parse(400, """{"error":{"type":"invalid_request_error","message":"prompt is too long: 220000 tokens > 200000 maximum"}}""")!!
        assertEquals(200000, claude.windowHint)
        val gemini = ModelContextRejected.parse(400, """{"error":{"status":"INVALID_ARGUMENT","message":"The input token count (1200000) exceeds the maximum number of tokens allowed (1048576)."}}""")!!
        assertEquals(1048576, gemini.windowHint)
        assertNull(ModelContextRejected.parse(400, """{"error":{"message":"max_tokens is too large"}}"""))
        val official = ModelApi.headers(ModelConfig("https://api.anthropic.com/v1", "c", "secret", "ANTHROPIC"), okhttp3.Request.Builder().url("https://api.anthropic.com/v1/models")).build()
        assertEquals("2026-10-21", official.header("anthropic-version")); assertEquals("Bearer secret", official.header("Authorization"))
        assertNull(official.header("x-api-key"))
    }
    @Test fun modelMetadataKeepsUnknownUnknownAndReadsProviderSpecificLimits() {
        val gemini = ModelApi.parseModels(JSONObject("""{"models":[{"name":"models/g","displayName":"G","inputTokenLimit":1048576,"outputTokenLimit":8192,"supportedGenerationMethods":["generateContent"]},{"name":"models/embed","supportedGenerationMethods":["embedContent"]}]}"""), ModelProtocol.GEMINI)
        assertEquals(1, gemini.size); assertEquals(1_048_576, gemini.single().context)
        val ordinary = ModelApi.parseModels(JSONObject("""{"data":[{"id":"x"},{"id":"y","context_length":131072},{"id":"z","context_window":1.5}]}"""), ModelProtocol.CHAT)
        assertNull(ordinary[0].context); assertEquals(131072, ordinary[1].context); assertNull(ordinary[2].context)
    }
}
