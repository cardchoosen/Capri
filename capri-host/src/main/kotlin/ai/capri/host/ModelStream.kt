package ai.capri.host

import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal const val MODEL_RESPONSE_BYTES = 8 * 1024 * 1024

internal fun modelStream(body: ResponseBody, onText: (String) -> Unit): JSONObject {
    val source = body.source()
    val content = StringBuilder()
    val reasoning = StringBuilder()
    val calls = sortedMapOf<Int, JSONObject>()
    var payloadCharacters = 0L
    var finish: String? = null
    var usage: JSONObject? = null
    while (!source.exhausted()) {
        val line = source.readUtf8LineStrict(256L * 1024)
        if (!line.startsWith("data:")) continue
        val data = line.removePrefix("data:").trimStart()
        if (data == "[DONE]") break
        payloadCharacters += data.length
        if (payloadCharacters > 64L * 1024 * 1024) throw IOException("Model event stream is too large.")
        val event = JSONObject(data)
        if (event.has("error")) throw IOException("The provider interrupted the model stream.")
        event.optJSONObject("usage")?.let { usage = it }
        val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: continue
        if (!choice.isNull("finish_reason")) finish = choice.getString("finish_reason")
        val delta = choice.optJSONObject("delta") ?: continue
        (delta.opt("reasoning_content") as? String)?.let { reasoning.append(it) }
        val text = delta.opt("content") as? String
        if (!text.isNullOrEmpty()) content.append(text)
        if (content.length + reasoning.length > MODEL_RESPONSE_BYTES)
            throw IOException("Model output exceeded the transport size limit.")
        if (!text.isNullOrEmpty()) onText(text)
        val tools = delta.optJSONArray("tool_calls") ?: continue
        for (i in 0 until tools.length()) {
            val part = tools.getJSONObject(i)
            val index = part.getInt("index")
            if (index < 0) throw IOException("Invalid tool index.")
            val call = calls.getOrPut(index) {
                JSONObject().put("type", "function").put("function", JSONObject().put("name", "").put("arguments", ""))
            }
            if (!part.isNull("id")) call.put("id", part.getString("id"))
            val function = part.optJSONObject("function") ?: continue
            val target = call.getJSONObject("function")
            for (key in listOf("name", "arguments")) {
                if (!function.isNull(key)) target.put(key, target.getString(key) + function.getString(key))
            }
        }
    }
    if (finish == null) throw IOException("The model stream ended without a completion status.")
    val message = JSONObject().put("role", "assistant").put("content", if (content.isEmpty()) JSONObject.NULL else content.toString())
    if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
    if (calls.isNotEmpty()) message.put("tool_calls", JSONArray(calls.values))
    return JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", finish)))
        .putOpt("usage", usage)
}
