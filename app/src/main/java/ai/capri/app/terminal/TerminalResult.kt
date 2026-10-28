package ai.capri.app.terminal

import org.json.JSONArray
import org.json.JSONObject

/** Large list histories can be omitted, but execution output/offsets are never silently cut. */
internal object TerminalResult {
    fun encode(result: JSONObject): String {
        if (result.toString().length <= 36000) return result.toString()
        result.optJSONObject("data")?.optJSONArray("terminals")?.let { terminals ->
            val minimal = JSONArray()
            for (i in 0 until terminals.length()) {
                val source = terminals.getJSONObject(i)
                minimal.put(JSONObject().apply {
                    for (key in listOf("terminal_id", "chat_id", "title", "state", "command_count")) if (source.has(key)) put(key, source.get(key))
                })
            }
            result.getJSONObject("data").put("terminals", minimal).put("history_omitted", true)
                .put("notice", "Command histories omitted to bound the response; attach to a terminal for its recent commands.")
        }
        val encoded = result.toString()
        if (encoded.length <= 36000) return encoded
        return ai.capri.app.ExecutionResult.annotate(JSONObject().put("action", result.optString("action")).put("status", "result_too_large")
            .put("message", "Result exceeds the response budget. Existing commands were not replayed or stopped; read bounded output using their returned IDs.")).toString()
    }
}
