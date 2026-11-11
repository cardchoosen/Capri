package ai.capri.host

import org.json.JSONArray
import org.json.JSONObject

internal fun recoverableTurn(turn: JSONObject): Boolean = turn.optString("state") in
    setOf("running", "failed", "timed_out", "budget", "incomplete", "cancelled")

internal fun jsonObjects(array: JSONArray): List<JSONObject> =
    (0 until array.length()).map(array::getJSONObject)

internal fun toolCallKey(call: JSONObject): String {
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    val function = call.getJSONObject("function")
    val raw = function.getString("arguments")
    val name = function.getString("name")
    val args = runCatching { JSONObject(raw) }.getOrNull()
    if (name == "tool_invoke" && args?.optJSONObject("arguments") != null)
        return args.optString("name") + ":" + canonical(args.getJSONObject("arguments"))
    return name + ":" + (args?.let(::canonical) ?: raw)
}

internal fun recoverToolPairs(messages: JSONArray, started: Set<String>): Set<String> {
    val original = jsonObjects(messages)
    val blocked = mutableSetOf<String>()
    val rebuilt = JSONArray()
    var cursor = 0
    while (cursor < original.size) {
        val message = original[cursor++]
        if (message.getString("role") == "tool") continue
        rebuilt.put(message)
        val calls = message.optJSONArray("tool_calls") ?: continue
        val tail = mutableListOf<JSONObject>()
        while (cursor < original.size && original[cursor].getString("role") !in setOf("assistant", "user")) tail.add(original[cursor++])
        val results = tail.filter { it.getString("role") == "tool" }.associateBy { it.getString("tool_call_id") }
        for (call in jsonObjects(calls)) {
            val id = call.getString("id")
            val result = results[id]
            val status = result?.let {
                runCatching { JSONObject(it.getString("content")).getString("status") }.getOrNull()
            }
            if (status in setOf("ok", "unknown") || result == null && id in started) blocked.add(toolCallKey(call))
            val missing = JSONObject().put("status", if (id in started) "unknown" else "not_executed")
                .put("notice", if (id in started) "The outcome is unknown. Verify the effect before proceeding."
                    else "The action had not started when the run stopped.")
            rebuilt.put(result ?: JSONObject().put("role", "tool").put("tool_call_id", id).put("content", missing.toString()))
        }
        for (item in tail) if (item.getString("role") != "tool") rebuilt.put(item)
    }
    for (i in messages.length() - 1 downTo 0) messages.remove(i)
    for (message in jsonObjects(rebuilt)) messages.put(message)
    return blocked
}
