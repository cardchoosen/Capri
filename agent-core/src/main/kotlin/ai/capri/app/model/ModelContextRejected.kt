package ai.capri.app.model

import org.json.JSONObject

/** Deliberately narrower than generic HTTP 400: an invalid parameter must never trigger compaction. */
class ModelContextRejected(val windowHint: Int?) : Exception("模型拒绝上下文长度") {
    companion object {
        fun parse(http: Int, raw: String): ModelContextRejected? {
            if (http !in setOf(400, 413, 422)) return null
            val error = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull() ?: return null
            val code = error.optString("code").lowercase()
            val message = error.optString("message").lowercase()
            val explicit = code in setOf("context_length_exceeded", "context_window_exceeded", "max_context_length_exceeded") ||
                (Regex("(maximum context length|context window|context length limit)").containsMatchIn(message) &&
                    Regex("(exceed|too long|maximum context length)").containsMatchIn(message))
            val anthropicOverflow = message.startsWith("prompt is too long")
            val geminiOverflow = message.contains("input token count") && message.contains("exceeds the maximum number of tokens")
            if (!explicit && !anthropicOverflow && !geminiOverflow) return null
            val hint = Regex("(?:maximum context length is|context (?:window|length limit)(?: is|:| of)?)\\s*([0-9][0-9,]*)", RegexOption.IGNORE_CASE)
                .find(message)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
                ?.takeIf { it in 1024..16_777_216 }?.toInt()
            val nativeHint = (Regex("([0-9][0-9,]*) maximum").find(message)?.groupValues?.get(1)
                ?: Regex("maximum number of tokens allowed \\(([0-9][0-9,]*)\\)").find(message)?.groupValues?.get(1))
            return ModelContextRejected(hint ?: nativeHint?.replace(",", "")?.toLongOrNull()?.takeIf { it in 1024..16_777_216 }?.toInt())
        }
    }
}
