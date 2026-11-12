package ai.capri.host

import ai.capri.app.agent.AgentLoop
import ai.capri.app.model.DeepSeekReasoning
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

internal data class HostModelSelection(val model: String, val effort: String, val outputTokens: Int?)

internal class HostModelProvider(endpoint: HttpUrl, private val defaultModel: String, private val limits: Map<String, Int>) {
    val deepSeek = endpoint.isHttps && endpoint.host == "api.deepseek.com" && endpoint.port == 443
    private val models = if (deepSeek) listOf(defaultModel, "deepseek-flash", "deepseek-v4-pro").distinct() else listOf(defaultModel)
    private val efforts = if (deepSeek) listOf("none", "low", "high", "max") else listOf("none")
    private val defaultEffort = if (deepSeek) DeepSeekReasoning.DEFAULT_EFFORT else "none"

    fun options(): JSONObject = JSONObject().put("models", JSONArray(models)).put("efforts", JSONArray(efforts))
        .put("defaultModel", defaultModel).put("defaultEffort", defaultEffort)

    fun selection(request: JSONObject): HostModelSelection? {
        val modelId = if (request.has("model")) request.opt("model") else defaultModel
        val effort = if (request.has("effort")) request.opt("effort") else defaultEffort
        if (modelId !is String || modelId !in models || effort !is String || effort !in efforts) return null
        return HostModelSelection(modelId, effort, limits[modelId])
    }

    fun request(input: AgentLoop.Input, selection: HostModelSelection, streaming: Boolean): JSONObject {
        val body = JSONObject().put("model", selection.model).put("messages", input.messages)
            .put("stream", streaming).putOpt("max_tokens", input.output.takeIf { it > 0 })
        if (input.tools.length() > 0) body.put("tools", input.tools)
        if (deepSeek) {
            if (streaming) body.put("stream_options", JSONObject().put("include_usage", true))
            body.put("thinking", JSONObject().put("type", if (selection.effort == "none") "disabled" else "enabled"))
            body.put("reasoning_effort", selection.effort)
        }
        return body
    }
}
