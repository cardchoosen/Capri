package ai.capri.app

import org.json.JSONObject

/** Preserve legacy status/phase/message fields; add a common diagnostic without exposing localized internals. */
internal object ExecutionResult {
    fun message(error: Throwable): String {
        if (error is RuntimeBusyException) return "Environment is busy or under maintenance. Wait for existing operations or close the relevant terminals; other tasks were not interrupted."
        val raw = error.message.orEmpty()
        return if (raw.isNotBlank() && raw.none { it in '\u4e00'..'\u9fff' }) raw.take(1500)
        else "Execution could not complete. Inspect the tool status, phase and available output before retrying."
    }
    fun textPage(text: String, offset: Int, maxChars: Int = 16000, jsonBudget: Int = 24000): String {
        require(offset >= 0) { "offset must be nonnegative." }
        val start = minOf(offset, text.length)
        require(start == text.length || start == 0 || !(text[start].isLowSurrogate() && text[start - 1].isHighSurrogate())) { "offset must start at a Unicode character boundary." }
        var low = 0; var high = minOf(maxChars, text.length - start)
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (JSONObject.quote(text.substring(start, start + middle)).length <= jsonBudget) low = middle else high = middle - 1
        }
        if (low > 0 && start + low < text.length && text[start + low - 1].isHighSurrogate() && text[start + low].isLowSurrogate()) low--
        return text.substring(start, start + low)
    }
    fun environment(result: JSONObject): String {
        annotate(result)
        if (result.toString().length <= 33000) return result.toString()
        result.remove("project"); result.remove("diagnostics")
        for (limit in listOf(512, 0)) {
            fun compact(source: JSONObject): JSONObject = JSONObject().apply {
                for (key in listOf("present", "satisfies", "passed", "exit_code", "elapsed_ms", "cached", "verified_at")) if (source.has(key)) put(key, source.get(key))
                if (limit > 0) for (key in listOf("path", "version", "error", "output")) if (source.has(key)) put(key, source.optString(key).take(limit))
                put("details_omitted", true)
            }
            result.optJSONObject("checks")?.let { checks -> checks.keys().asSequence().toList().forEach { key -> checks.optJSONObject(key)?.let { checks.put(key, compact(it)) } } }
            result.optJSONArray("operations")?.let { steps -> for (i in 0 until steps.length()) steps.optJSONObject(i)?.let { steps.put(i, compact(it)) } }
            result.optJSONObject("verification")?.let { result.put("verification", compact(it)) }
            for (key in listOf("evidence", "message")) if (result.opt(key) is String) result.put(key, result.getString(key).take(limit))
            result.put("details_omitted", true).put("notice", "Large diagnostics omitted. Check declared requirements, exit codes and satisfaction flags; use narrower requests for detail. Do not replay completed operations merely to retrieve logs.")
            if (result.toString().length <= 33000) return result.toString()
        }
        // Keep the actual outcome even for an unexpectedly large backend envelope.
        return JSONObject().put("status", result.optString("status", "error")).put("details_omitted", true)
            .put("requirements_satisfied", result.optBoolean("requirements_satisfied", false))
            .put("notice", "Detailed result exceeds the response budget. Satisfaction is unconfirmed unless explicitly true; inspect narrower evidence before continuing.").toString()
    }
    fun annotate(result: JSONObject): JSONObject {
        val status = result.optString("status")
        if (status.isNotBlank() && status != "ok" && !result.has("error")) {
            result.put("error", JSONObject().put("code", status).put("message", result.optString("message").ifBlank { "Operation did not complete; inspect phase, exit code and evidence." }))
        }
        return result
    }
}
