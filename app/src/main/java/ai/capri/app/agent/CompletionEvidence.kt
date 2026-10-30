package ai.capri.app.agent

import org.json.JSONArray
import org.json.JSONObject

/** Checks provenance and explicit failure signals, never claims to prove arbitrary user goals. */
internal object CompletionEvidence {
    private val bookkeeping = setOf("task_state", "tool_search", "tool_describe", "ask_user", "model_info", "profile_search", "skill")
    private val reads = setOf("read_file", "list_files", "search_files", "project", "coding_reference", "coding_diagnostics", "web_search", "web_read", "context_history", "conversation_history", "personal_memory", "knowledge")
    private fun administrative(call: JSONObject): Boolean {
        val f=runCatching { ToolRegistry.function(call) }.getOrDefault(call.getJSONObject("function"))
        return f.optString("name") in bookkeeping || f.optString("name")=="program_run" &&
            runCatching { JSONObject(f.optString("arguments")).optString("action") in setOf("status","reset","read") }.getOrDefault(false)
    }
    private fun prior(messages: JSONArray): Set<String> = TurnState.snapshot(messages).optJSONArray("prior_calls")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    private fun calls(messages: JSONArray): Map<String, JSONObject> = (0 until messages.length()).flatMap { i ->
        val a = messages.getJSONObject(i).optJSONArray("tool_calls") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }
    }.filterNot { it.getString("id") in prior(messages) }.associateBy { it.getString("id") }
    fun results(messages: JSONArray): List<JSONObject> {
        val calls = calls(messages)
        return (0 until messages.length()).map { messages.getJSONObject(it) }.filter {
            it.optString("role") == "tool" && it.optString("tool_call_id") !in prior(messages) && calls[it.optString("tool_call_id")]?.let(::administrative) != true
        }
    }
    fun requiresConclusion(messages: JSONArray): Boolean {
        val state = TaskState.snapshot(messages)
        val request = state.optString("request") + "\n" + state.optJSONArray("user_context").toString()
        if (ai.capri.app.TurnCompletion.requiresEvidence(request) || (state.optJSONArray("steps")?.length() ?: 0) > 0) return true
        return calls(messages).values.filterNot(::administrative).any { val f = runCatching { ToolRegistry.function(it) }.getOrDefault(it.getJSONObject("function")); f.getString("name") !in bookkeeping + reads }
    }
    fun unsuccessful(text: String): Boolean {
        if (text.startsWith("exit=")) return !text.startsWith("exit=0\n") && text != "exit=0"
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return text.startsWith("工具失败")
        fun bad(obj: JSONObject, depth: Int): Boolean {
            if (obj.optBoolean("result_incomplete") || obj.optBoolean("isError") || obj.optString("outcome") == "unknown") return true
            if (obj.optString("status") in setOf("tool_error", "mcp_call_failed", "unavailable", "not_found", "error", "partial", "partial_failure", "project_rules_required", "timeout", "failed", "permission_required", "permission_denied", "execution_failed", "preparation_failed", "invalid_argument", "cancelled", "not_executed", "awaiting_user") || obj.optString("state") in setOf("running", "pending", "failed", "cancelled", "timeout")) return true
            if (obj.has("success") && obj.opt("success") == false || obj.has("exit_code") && !obj.isNull("exit_code") && obj.optInt("exit_code", -1) != 0 || obj.has("error") && !obj.isNull("error")) return true
            return depth < 3 && listOf("data", "command", "result").any { obj.optJSONObject(it)?.let { child -> bad(child, depth + 1) } == true }
        }
        return bad(root, 0)
    }
    private fun positive(text: String): Boolean {
        if (unsuccessful(text)) return false
        if (text.startsWith("exit=0\n") || text == "exit=0") return true
        val value = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return value.optString("status") in setOf("ok", "success", "completed", "done") || value.opt("success") == true || value.optString("state") in setOf("completed", "exited")
    }
    fun conclude(messages: JSONArray, args: JSONObject): JSONObject {
        require(args.keys().asSequence().all { it in setOf("action", "outcome", "summary", "evidence") }) { "finish accepts action, outcome, summary and evidence only." }
        val outcome = args.optString("outcome")
        require(outcome in setOf("completed", "partial", "blocked")) { "outcome must be completed, partial or blocked." }
        require(args.opt("summary") is String && args.getString("summary").isNotBlank() && args.getString("summary").length <= 2000) { "summary must contain 1..2000 characters." }
        val refs = args.optJSONArray("evidence") ?: error("evidence must be an array of actual tool call IDs.")
        require(refs.length() <= 16 && (0 until refs.length()).all { refs.opt(it) is String }) { "Use at most 16 evidence call IDs." }
        val ids = (0 until refs.length()).map { refs.getString(it) }
        require(ids.distinct().size == ids.size) { "Evidence references must be unique." }
        val records = results(messages); val available = records.associateBy { it.getString("tool_call_id") }
        require(ids.all { it in available }) { "Evidence must reference recorded work results, not plans or invented IDs." }
        if (outcome == "completed") {
            val steps = TaskState.snapshot(messages).optJSONArray("steps") ?: JSONArray()
            require((0 until steps.length()).none { steps.getJSONObject(it).optString("status") != "completed" }) { "Unfinished plan steps cannot be concluded completed." }
            require(!requiresConclusion(messages) || ids.isNotEmpty()) { "Completed action tasks require recorded work evidence." }
            require(ids.all { positive(available.getValue(it).optString("content")) }) { "Failed, partial, pending or unknown work is not successful completion evidence." }
        }
        return JSONObject().put("outcome", outcome).put("summary", args.getString("summary")).put("evidence", refs)
            .put("work_results", records.size)
    }
    fun issue(messages: JSONArray): String? {
        val state = TaskState.snapshot(messages)
        val finish = state.optJSONObject("conclusion")
        if (finish != null && finish.optInt("work_results") == results(messages).size) return null
        if (requiresConclusion(messages)) return "Before delivery, reconcile the plan with actual results and call task_state action=finish with outcome completed/partial/blocked, a factual summary and actual work tool call IDs in evidence. A successful invocation is not proof of the user's goal. Verify artifacts and report remaining failures; never fabricate evidence or mark steps complete merely to pass this check."
        return null
    }
}
