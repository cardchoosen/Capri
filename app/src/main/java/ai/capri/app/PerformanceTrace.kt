package ai.capri.app

import org.json.JSONObject
import java.io.File

/** Numeric operational metadata only. Never pass prompts, arguments, outputs, URLs or exception text. */
internal class PerformanceTrace(private val file: File, private val clock: () -> Long = System::nanoTime) {
    enum class Stage { TURN, FINAL_REPLY, MODEL_REQUEST, MODEL_ADMISSION, MODEL_PREPARE, MODEL_QUEUE, MODEL_HEADERS, MODEL_BODY, MODEL_PARSE, MODEL_BACKOFF, TOOL, PREPARE, EXECUTE, STEP, ENVIRONMENT, TURN_PREPARE, CONTEXT_PREPARE, DELIVERY_PREPARE }
    enum class Status { STARTED, OK, RETURNED, ERROR, CANCELLED, TIMEOUT, BUDGET, INCOMPLETE, WAITING }
    private val origin = clock()
    private var closed = false
    companion object {
        private val tools = setOf("ask_user", "task_state", "tool_search", "tool_describe", "tool_invoke", "program_run", "task_experience", "agent_delegate", "agent_jobs", "coding_diagnostics", "coding_reference", "coding_restore", "extensions", "project", "apply_patch", "search_files", "edit_file", "model_info", "profile_search", "list_files", "read_file", "write_file", "send_file", "python_run", "environment", "environment_check", "python_env", "android", "terminal", "browser")
        private val usageFields = setOf("prompt_tokens", "completion_tokens", "total_tokens", "prompt_cache_hit_tokens", "prompt_cache_miss_tokens")
        fun now() = System.nanoTime()
        fun elapsed(start: Long) = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(0)
    }
    init { event(Stage.TURN, 0, Status.STARTED) }
    @Synchronized fun control(phase: String, budget: JSONObject) {
        runCatching {
            if (closed || file.length() >= 262144) return
            require(phase in ai.capri.app.agent.TurnState.Phase.entries.map { it.name })
            val clean = JSONObject()
            listOf("maximum", "used", "remaining", "execution_remaining", "reserved_delivery", "retries").forEach { clean.put(it, budget.optInt(it).coerceAtLeast(0)) }
            file.parentFile!!.mkdirs(); file.appendText(JSONObject().put("schema", 1).put("stage", "task_phase").put("phase", phase.lowercase()).put("budget", clean)
                .put("offset_ms", ((clock() - origin) / 1_000_000).coerceAtLeast(0)).toString() + "\n")
        }
    }
    @Synchronized fun deliveryRoute(route: String) {
        runCatching {
            if (closed || file.length() >= 262144) return
            require(route in setOf("persona", "direct_exact_json"))
            file.parentFile!!.mkdirs(); file.appendText(JSONObject().put("schema", 1).put("stage", "delivery_route").put("route", route)
                .put("offset_ms", ((clock() - origin) / 1_000_000).coerceAtLeast(0)).toString() + "\n")
        }
    }
    @Synchronized fun toolSurface(report: JSONObject) {
        runCatching {
            if (closed || file.length() >= 262144) return
            val clean = JSONObject()
            listOf("available", "offered", "loaded", "loaded_schema_tokens", "schema_budget", "searches", "empty_searches", "evictions", "schema_changes", "invocations", "bridge_invocations", "used_unique", "rejected", "resolution_ms_total").forEach { clean.put(it, report.optLong(it).coerceAtLeast(0)) }
            file.parentFile!!.mkdirs(); file.appendText(JSONObject().put("schema", 1).put("stage", "tool_surface").put("counts", clean)
                .put("offset_ms", ((clock() - origin) / 1_000_000).coerceAtLeast(0)).toString() + "\n")
        }
    }
    @Synchronized fun context(messages: org.json.JSONArray, tools: org.json.JSONArray) {
        runCatching {
            if (closed || file.length() >= 262144) return
            val system = (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.optString("role") == "system" }
                .sumOf { ai.capri.app.model.ContextTokens.message(it) }
            val event = JSONObject().put("schema", 1).put("stage", "context")
                .put("offset_ms", ((clock() - origin) / 1_000_000).coerceAtLeast(0))
                .put("estimated_system_tokens", system).put("estimated_tool_tokens", ai.capri.app.model.ContextTokens.text(tools.toString()))
                .put("estimated_total_tokens", ai.capri.app.model.ContextTokens.estimate(messages, tools))
                .put("message_count", messages.length()).put("tool_count", tools.length())
            file.parentFile!!.mkdirs(); file.appendText(event.toString() + "\n")
        }
    }
    @Synchronized fun modelDecision(choice: JSONObject, toolCount: Int, decision: String, corrections: Int) {
        runCatching {
            if (closed || file.length() >= 262144) return
            fun reason(value: String) = value.takeIf { it in setOf("stop", "tool_calls", "function_call", "length", "content_filter", "completed", "incomplete", "end_turn", "tool_use", "max_tokens", "stop_sequence", "pause_turn", "STOP", "MAX_TOKENS", "SAFETY", "RECITATION") } ?: "unknown"
            val event = JSONObject().put("schema", 1).put("stage", "model_decision")
                .put("offset_ms", ((clock() - origin) / 1_000_000).coerceAtLeast(0))
                .put("finish_reason", reason(choice.optString("finish_reason")))
                .put("native_finish_reason", reason(choice.optString("native_finish_reason", choice.optString("finish_reason"))))
                .put("tool_calls", toolCount.coerceAtLeast(0)).put("corrections", corrections.coerceIn(0, 2))
                .put("decision", decision.takeIf { it in setOf("received", "continue_preamble", "incomplete_preamble", "accepted_final") } ?: "unknown")
            file.parentFile!!.mkdirs(); file.appendText(event.toString() + "\n")
        }
    }
    @Synchronized fun event(stage: Stage, durationMs: Long, status: Status, attempt: Int? = null,
                            tool: String? = null, step: Int? = null, http: Int? = null, usage: JSONObject? = null, details: JSONObject? = null, purpose: ModelRequests.Purpose? = null) {
        // Diagnostics must never break a user task. Bounded per-turn append, scoped under the chat.
        runCatching {
            if (closed) return
            if (file.length() >= 262144) return
            val event = JSONObject().put("schema", 1).put("stage", stage.name.lowercase())
                .put("offset_ms", ((clock() - origin) / 1_000_000).coerceAtLeast(0))
                .put("duration_ms", durationMs.coerceAtLeast(0)).put("status", status.name.lowercase())
            purpose?.let { event.put("purpose", it.name.lowercase()) }
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
