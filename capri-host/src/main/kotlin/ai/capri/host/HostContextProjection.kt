package ai.capri.host

import ai.capri.app.model.ContextTokens
import org.json.JSONArray
import org.json.JSONObject

internal fun projectHostContext(messages: JSONArray, tools: JSONArray, budget: Long): JSONArray {
    if (ContextTokens.estimate(messages, tools) <= budget) return messages
    val original = jsonObjects(messages)
    val projected = original.toMutableList()
    for (i in original.indices) {
        val entry = original[i]
        if (entry.optString("role") != "tool") continue
        val content = entry.getString("content")
        if (content.length <= 2500) continue
        val result = runCatching { JSONObject(content) }.getOrNull()
        val preview = JSONObject().put("status", result?.optString("status") ?: "unknown")
            .putOpt("error", result?.opt("error")).putOpt("attachment", result?.opt("attachment"))
            .put("excerpt", content.take(2000)).put("truncated", true)
            .put("notice", "Use context_history with this tool call ID to read more of the recorded result.")
        projected[i] = JSONObject(entry.toString()).put("content", preview.toString())
    }
    var candidate = JSONArray(projected)
    if (ContextTokens.estimate(candidate, tools) <= budget) return candidate

    val latestUser = original.indexOfLast { it.optString("role") == "user" }
    val latestAssistant = original.indexOfLast { it.optString("role") == "assistant" }
    val removed = mutableSetOf<Int>()
    val evidence = ArrayDeque<JSONObject>()
    for (i in original.indices) {
        val assistant = original[i]
        if (i == latestAssistant || assistant.optString("role") != "assistant") continue
        val calls = assistant.optJSONArray("tool_calls")
        if (calls != null) {
            val ids = jsonObjects(calls).map { it.getString("id") }.toSet()
            val results = (i + 1 until original.size).takeWhile { original[it].optString("role") !in setOf("user", "assistant") }
                .filter { original[it].optString("role") == "tool" && original[it].optString("tool_call_id") in ids }
            if (results.size != ids.size || results.map { original[it].getString("tool_call_id") }.toSet() != ids) continue
            val names = jsonObjects(calls).associate { it.getString("id") to it.getJSONObject("function").getString("name") }
            for (index in results) {
                val item = original[index]
                val id = item.getString("tool_call_id")
                val outcome = runCatching { JSONObject(item.getString("content")) }.getOrNull()
                evidence.addLast(JSONObject().put("toolCallId", id.take(200)).put("tool", names.getValue(id).take(100))
                    .put("status", (outcome?.optString("status") ?: "recorded").take(80)))
                if (evidence.size > 16) evidence.removeFirst()
            }
            removed.addAll(results)
        } else if (i >= latestUser) continue
        removed.add(i)
        val retained = projected.filterIndexed { index, _ -> index !in removed }.toMutableList()
        if (evidence.isNotEmpty()) {
            val note = "Earlier settled tool exchanges were shortened. The following is receipt metadata only; " +
                "use context_history with a tool call ID for the full result. Do not infer success from a missing receipt. " +
                JSONArray(evidence.toList())
            val firstNonSystem = retained.indexOfFirst { it.optString("role") != "system" }.coerceAtLeast(0)
            retained.add(firstNonSystem, JSONObject().put("role", "system").put("content", note))
        }
        candidate = JSONArray(retained)
        if (ContextTokens.estimate(candidate, tools) <= budget) return candidate
    }
    throw HostContextBudgetExceeded()
}

internal class HostContextBudgetExceeded : IllegalStateException("The protected user input and tool context exceed the host input budget.")
