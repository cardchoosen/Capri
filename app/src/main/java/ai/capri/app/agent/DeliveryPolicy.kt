package ai.capri.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/** Skip voice rendering only when an explicit exact-format request already has valid JSON. */
internal object DeliveryPolicy {
    private val exact = Regex("(?:只|仅)(?:需|要|能)?(?:输出|返回|提供|给出|给我).{0,12}JSON|(?:输出|返回).{0,8}(?:纯|严格)JSON|(?:only|just|strictly)\\s+(?:(?:output|return|provide)\\s+)?(?:a\\s+)?(?:valid\\s+|raw\\s+|pure\\s+)?JSON|(?:output|return|provide)\\s+(?:a\\s+)?(?:valid\\s+)?JSON(?:\\s+(?:object|array))?\\s+only", RegexOption.IGNORE_CASE)
    private val override = Regex("(?:不|别|不要|不用|无需).{0,8}(?:只|仅|纯|严格)?.{0,8}JSON|(?:don't|do not|not|no longer).{0,20}(?:only|JSON)", RegexOption.IGNORE_CASE)
    private fun strictValue(value: JsonElement, depth: Int = 0): Boolean {
        if (depth > 64) return false
        return when (value) {
            is JsonObject -> value.values.all { strictValue(it, depth + 1) }
            is JsonArray -> value.all { strictValue(it, depth + 1) }
            is JsonPrimitive -> value.isString || value.content in setOf("true", "false", "null") || Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""").matches(value.content)
        }
    }
    fun exactJson(state: JSONObject, execution: JSONArray): String? {
        val applied = TurnState.snapshot(execution).optJSONArray("applied") ?: return null
        if (applied.length() != 1) return null // Corrections require a fresh rendering decision.
        val task = state.optJSONObject("task_state") ?: return null
        val request = task.optString("request")
        if (!exact.containsMatchIn(request) || override.containsMatchIn(request)) return null
        if (task.optJSONObject("conclusion")?.optString("outcome") in setOf("partial", "blocked")) return null
        val draft = state.optString("draft").trim()
        if (draft.length !in 2..128000 || draft.first() !in setOf('{', '[')) return null
        val parsed = runCatching { Json.parseToJsonElement(draft) }.getOrNull()
        return if ((parsed is JsonObject || parsed is JsonArray) && strictValue(parsed)) draft else null
    }
}
