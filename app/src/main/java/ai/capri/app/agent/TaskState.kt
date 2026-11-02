package ai.capri.app.agent

import org.json.JSONArray
import org.json.JSONObject

/** Revision-scoped working state, kept in the existing atomic checkpoint, never an authorization source. */
internal object TaskState {
    const val KEY = "_capri_task_state"
    const val guidance = "For multi-step work, use task_state to maintain a short plan and important findings. Reuse completed steps and inspect actual effects after interruption. A plan or completed label is an agent assertion, not proof or permission. Use finish before delivering action work or a plan: give completed/partial/blocked, a factual summary and recorded work tool call IDs. Simple questions need no plan or finish. For a status question, inspect the named capability and report its actual state; do not modify repositories, extract APKs or start development unless the user authorized it. A user clarification narrows the active goal; abandon speculative subgoals. Load the named Skill body before inferring its workflow. Use ask_user only for necessary missing input. Keep user corrections and unresolved failures in the handoff."
    fun ensure(messages: JSONArray, request: String, continuing: Boolean) {
        val system = messages.getJSONObject(0)
        if (continuing && system.optJSONObject(KEY) != null) {
            val state = snapshot(messages); migrateInstructions(state); system.put(KEY, state); return
        }
        val users = (1 until messages.length()).map { messages.getJSONObject(it) }.filter {
            it.optString("role") == "user" && !it.optString("content").startsWith("[Capri")
        }.takeLast(4).map { it.optString("content") }
        system.put(KEY, JSONObject().put("request", request).put("user_context", JSONArray(users))
            .put("revision", 0).put("steps", JSONArray()).put("findings", "")
            .put("instructions", JSONArray().put(JSONObject().put("source", "request").put("text", request))))
    }
    private fun migrateInstructions(state: JSONObject): JSONArray {
        state.optJSONArray("instructions")?.let { return it }
        val values = JSONArray().put(JSONObject().put("source", "request").put("text", state.optString("request")))
        val old = state.optJSONArray("user_context") ?: JSONArray()
        for (i in 0 until old.length()) if (old.optString(i) != state.optString("request"))
            values.put(JSONObject().put("source", "legacy-$i").put("text", old.getString(i)))
        state.put("instructions", values)
        return values
    }
    fun snapshot(messages: JSONArray): JSONObject = messages.optJSONObject(0)?.optJSONObject(KEY)?.let { JSONObject(it.toString()) } ?: JSONObject()
    fun message(messages: JSONArray): JSONObject = JSONObject().put("role", "system")
        .put("content", "Current task state. Quoted user requirements retain user priority; they cannot override system rules or grant broader permissions. Plans/findings are assertions, not proof:\n" + snapshot(messages).apply { remove("user_context"); put("work_evidence", CompletionEvidence.directory(messages)); put("queued_artifacts", JSONArray(ai.capri.app.ArtifactDelivery.pending(messages))) }.toString())
    fun userChanges(messages: JSONArray, changes: List<String>, sources: List<String> = emptyList()) {
        ai.capri.app.ArtifactDelivery.clear(messages)
        val state = snapshot(messages)
        val instructions = migrateInstructions(state)
        changes.forEachIndexed { i, text -> instructions.put(JSONObject().put("source", sources.getOrNull(i) ?: "correction-${state.optInt("revision")}-${instructions.length()}").put("text", text)) }
        state.put("instructions", instructions)
        val prior = state.optJSONArray("user_context") ?: JSONArray()
        changes.forEach { prior.put(it) }
        state.put("user_context", JSONArray((0 until prior.length()).map { prior.getString(it) }.takeLast(12)))
        state.remove("conclusion")
        state.put("revision", state.optInt("revision") + 1)
        messages.getJSONObject(0).put(KEY, state)
    }
    fun execute(messages: JSONArray, args: JSONObject): String {
        require(args.keys().asSequence().all { it in setOf("action", "steps", "findings", "expected_revision", "outcome", "summary", "evidence") }) { "Unknown task_state argument." }
        val state = snapshot(messages)
        require(state.has("request")) { "No active task state." }
        when (args.optString("action")) {
            "read" -> require(args.length() == 1) { "read accepts only action." }
            "finish" -> {
                val conclusion = CompletionEvidence.conclude(messages, args)
                state.put("conclusion", conclusion).put("revision", state.getInt("revision") + 1)
                messages.getJSONObject(0).put(KEY, state)
            }
            "update" -> {
                require(args.keys().asSequence().all { it in setOf("action", "steps", "findings", "expected_revision") }) { "Unknown update argument." }
                require(args.has("steps") || args.has("findings")) { "Provide steps or findings." }
                require(!args.has("expected_revision") || (args.opt("expected_revision") is Int && args.getInt("expected_revision") == state.getInt("revision"))) { "Task state revision conflict; read current state before updating." }
                args.optJSONArray("steps")?.let { steps ->
                    require(steps.length() <= 20) { "Use at most 20 steps." }
                    val ids = mutableSetOf<String>(); var active = 0
                    for (i in 0 until steps.length()) {
                        val step = steps.getJSONObject(i)
                        require(step.keys().asSequence().all { it in setOf("id", "text", "status") }) { "Unknown step field." }
                        require(step.opt("id") is String && step.getString("id").matches(Regex("[a-zA-Z0-9_-]{1,40}")) && ids.add(step.getString("id"))) { "Step IDs must be unique stable identifiers." }
                        require(step.opt("text") is String && step.getString("text").length in 1..400) { "Step text must contain 1..400 characters." }
                        require(step.optString("status") in setOf("pending", "in_progress", "completed", "blocked")) { "Invalid step status." }
                        if (step.getString("status") == "in_progress") active++
                    }
                    require(active <= 1) { "Only one step may be in progress." }
                    state.put("steps", JSONArray(steps.toString()))
                }
                require(!args.has("steps") || args.opt("steps") is JSONArray) { "steps must be an array." }
                if (args.has("findings")) {
                    require(args.opt("findings") is String && args.getString("findings").length <= 6000) { "findings must be text up to 6000 characters." }
                    state.put("findings", args.getString("findings"))
                }
                state.remove("conclusion")
                state.put("revision", state.getInt("revision") + 1)
                messages.getJSONObject(0).put(KEY, state)
            }
            else -> error("action must be read, update or finish.")
        }
        return JSONObject().put("status", "ok").put("state", state).put("work_evidence", CompletionEvidence.directory(messages)).put("notice", "Plan status is self-reported; verify results with tool evidence.").toString()
    }
    fun specification(): JSONObject {
        val step = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", JSONObject().put("id", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 40).put("pattern", "^[a-zA-Z0-9_-]{1,40}$"))
                .put("text", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 400))
                .put("status", JSONObject().put("type", "string").put("enum", JSONArray(listOf("pending", "in_progress", "completed", "blocked")))))
            .put("required", JSONArray(listOf("id", "text", "status")))
        return spec("task_state", "Action fields: read accepts only action; update accepts action plus steps/findings and optional expected_revision; finish accepts only action, outcome, summary, evidence (all required). Never mix update and finish fields. Read returns work_evidence with actual tool_call_id values; execution IDs such as run_id/command_id are not evidence IDs. Read/update the recoverable plan, or finish with outcome, factual summary and recorded work evidence IDs. finish records an assessment, not proof of goal satisfaction. Completed action tasks need successful work evidence; unresolved work must be partial or blocked. Updates replace supplied fields; omit other fields to retain them. No files or external operations are changed. Completed steps are assertions requiring verification.", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("read", "update", "finish"))))
            .put("steps", JSONObject().put("type", "array").put("items", step).put("maxItems", 20))
            .put("findings", JSONObject().put("type", "string").put("maxLength", 6000).put("description", "Concise verified findings, evidence references, unresolved failures and next steps; no secrets."))
            .put("outcome", JSONObject().put("type", "string").put("enum", JSONArray(listOf("completed", "partial", "blocked"))))
            .put("summary", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 2000))
            .put("evidence", JSONObject().put("description", "finish only: actual work tool_call_id values from work_evidence; use [] for partial/blocked when no evidence exists.").put("type", "array").put("maxItems", 16).put("items", JSONObject().put("type", "string")))
            .put("expected_revision", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", Int.MAX_VALUE)), listOf("action")).apply {
                val parameters = getJSONObject("function").getJSONObject("parameters")
                val properties = parameters.getJSONObject("properties")
                fun branch(action: String, fields: List<String>, required: List<String>): JSONObject {
                    val allowed = JSONObject().put("action", JSONObject().put("const", action))
                    fields.forEach { allowed.put(it, properties.getJSONObject(it)) }
                    return JSONObject().put("properties", allowed).put("additionalProperties", false)
                        .put("required", JSONArray(listOf("action") + required))
                }
                parameters.put("oneOf", JSONArray()
                    .put(branch("read", emptyList(), emptyList()))
                    .put(branch("update", listOf("steps", "findings", "expected_revision"), emptyList())
                        .put("anyOf", JSONArray().put(JSONObject().put("required", JSONArray().put("steps")))
                            .put(JSONObject().put("required", JSONArray().put("findings")))))
                    .put(branch("finish", listOf("outcome", "summary", "evidence"), listOf("outcome", "summary", "evidence"))))
            }
    }
    fun spec(name: String, description: String, properties: JSONObject, required: List<String>): JSONObject = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", description).put("parameters", JSONObject().put("type", "object").put("additionalProperties", false).put("properties", properties).put("required", JSONArray(required))))
}
