package ai.capri.app

import org.json.JSONObject
import java.io.File

/** Numeric operational metadata only. Never pass prompts, arguments, outputs, URLs or exception text. */
internal class PerformanceTrace(private val file: File, private val clock: () -> Long = System::nanoTime) {
    enum class Stage { TURN, MODEL_REQUEST, MODEL_HEADERS, MODEL_BODY, MODEL_PARSE, MODEL_BACKOFF, TOOL, PREPARE, EXECUTE, STEP, ENVIRONMENT }
    enum class Status { STARTED, OK, RETURNED, ERROR, CANCELLED, TIMEOUT, BUDGET }
    private val origin = clock()
    private var closed = false
    companion object {
        private val tools = setOf("project", "apply_patch", "search_files", "edit_file", "model_info", "profile_search", "list_files", "read_file", "write_file", "send_file", "python_run", "environment", "environment_check", "python_env", "android", "terminal", "browser")
        private val usageFields = setOf("prompt_tokens", "completion_tokens", "total_tokens", "prompt_cache_hit_tokens", "prompt_cache_miss_tokens")
        fun now() = System.nanoTime()
        fun elapsed(start: Long) = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(0)
    }
    init { event(Stage.TURN, 0, Status.STARTED) }
    @Synchronized fun event(stage: Stage, durationMs: Long, status: Status, attempt: Int? = null,
                            tool: String? = null, step: Int? = null, http: Int? = null, usage: JSONObject? = null, details: JSONObject? = null) {
        // Diagnostics must never break a user task. Bounded per-turn append, scoped under the chat.
        runCatching {
            if (closed) return
            if (file.length() >= 262144) return
            val event = JSONObject().put("schema", 1).put("stage", stage.name.lowercase())
                .put("offset_ms", ((clock() - origin) / 1_000_000).coerceAtLeast(0))
                .put("duration_ms", durationMs.coerceAtLeast(0)).put("status", status.name.lowercase())
            attempt?.let { event.put("attempt", it) }; step?.let { event.put("step", it) }
            http?.let { event.put("http", it) }
            tool?.let { event.put("tool", if (it in tools) it else "unknown") }
            details?.let { raw ->
                val clean = JSONObject()
                setOf("queue", "session", "system_check", "system_install", "package_operations", "inspect").forEach { key ->
                    (raw.opt(key) as? Number)?.let { value -> if (value.toDouble().isFinite() && value.toDouble() >= 0 && value.toDouble() <= Long.MAX_VALUE.toDouble()) clean.put(key, value.toLong()) }
                }
                if (clean.length() > 0) event.put("detail_ms", clean)
            }
            usage?.let { raw ->
                val clean = JSONObject()
                usageFields.forEach { key -> (raw.opt(key) as? Number)?.let { number ->
                    if (number.toDouble().isFinite() && number.toDouble() >= 0 && number.toDouble() <= Long.MAX_VALUE.toDouble()) clean.put(key, number.toLong())
                } }
                if (clean.length() > 0) event.put("usage", clean)
            }
            file.parentFile!!.mkdirs(); file.appendText(event.toString() + "\n")
        }
    }
    @Synchronized fun finish(status: Status) {
        if (closed) return
        event(Stage.TURN, ((clock() - origin) / 1_000_000).coerceAtLeast(0), status)
        closed = true
    }
}
