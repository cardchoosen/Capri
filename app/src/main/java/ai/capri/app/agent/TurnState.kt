package ai.capri.app.agent

import ai.capri.app.Entry
import org.json.JSONArray
import org.json.JSONObject

/** Checkpoint-owned control state. Session entries are the durable steering inbox. */
internal object TurnState {
    const val KEY = "_capri_turn"
    enum class Phase(val label: String) {
        PREPARING("准备任务"), THINKING("思考中"), TOOLS("执行工具"), RECOVERING("恢复任务"),
        DELIVERING("整理回复"), WAITING("等待补充信息"), COMPLETED("已完成"), INCOMPLETE("尚未完成"),
        BUDGET("请求预算已用尽"), CANCELLED("已停止"), FAILED("执行中断")
    }
    class Redirect : Exception("New user input requires reassessment before delivery.")
    fun snapshot(messages: JSONArray?): JSONObject = messages?.optJSONObject(0)?.optJSONObject(KEY)?.let { JSONObject(it.toString()) } ?: JSONObject()
    fun ensure(messages: JSONArray, origin: Entry, continuing: Boolean) {
        if (!continuing || !messages.getJSONObject(0).has(KEY)) messages.getJSONObject(0).put(KEY,
            JSONObject().put("origin", origin.id).put("applied", JSONArray().put(origin.id)).put("phase", Phase.PREPARING.name).put("prior_calls", JSONArray((0 until messages.length()).flatMap { i ->
                val a = messages.getJSONObject(i).optJSONArray("tool_calls") ?: JSONArray()
                (0 until a.length()).map { a.getJSONObject(it).getString("id") }
            })))
    }
    private fun state(messages: JSONArray) = messages.getJSONObject(0).getJSONObject(KEY)
    fun phase(messages: JSONArray, phase: Phase) { state(messages).put("phase", phase.name) }
    fun pending(messages: JSONArray, entries: List<Entry>): List<Entry> {
        val s = snapshot(messages)
        val applied = s.optJSONArray("applied") ?: JSONArray()
        val ids = (0 until applied.length()).map { applied.getString(it) }.toSet()
        val origin = entries.indexOfFirst { it.id == s.optString("origin") }
        return if (origin < 0) emptyList() else entries.drop(origin + 1).filter { it.role == "user" && it.control in setOf("steering", "answer") && it.id !in ids }
    }
    fun apply(messages: JSONArray, entries: List<Entry>): Boolean {
        val changes = pending(messages, entries)
        if (changes.isEmpty()) return false
        val s = state(messages); val ids = s.getJSONArray("applied")
        changes.forEach { entry ->
            messages.put(JSONObject().put("role", "user").put("content", entry.text + ai.capri.app.InputAttachments.manifest(entry.inputs?.let { raw -> JSONArray(raw).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } } ?: emptyList())).apply {
                entry.inputs?.let { put(ai.capri.app.InputAttachments.KEY, JSONArray(it)) }
            })
            ids.put(entry.id)
        }
        s.remove("question"); s.remove("progress")
        TaskState.userChanges(messages, changes.map { it.text })
        return true
    }
    fun waitForUser(messages: JSONArray, args: JSONObject): String {
        require(args.keys().asSequence().all { it in setOf("question", "options") }) { "Unknown ask_user argument." }
        require(args.opt("question") is String && args.getString("question").isNotBlank() && args.getString("question").length <= 2000) { "question must contain 1..2000 characters." }
        if (args.has("options")) {
            val options = args.optJSONArray("options") ?: error("options must be an array.")
            require(options.length() in 2..5 && (0 until options.length()).all { options.opt(it) is String && options.getString(it).isNotBlank() && options.getString(it).length <= 200 }) { "Use 2..5 nonempty options of at most 200 characters." }
        }
        state(messages).put("question", JSONObject(args.toString()))
        return JSONObject().put("status", "awaiting_user").put("question", args.getString("question"))
            .put("notice", "The task will pause after this batch is settled. The user's next answer resumes the saved task; do not invent an answer or dispatch further actions.").toString()
    }
    fun question(messages: JSONArray): JSONObject? = snapshot(messages).optJSONObject("question")
    fun specification(): JSONObject = TaskState.spec("ask_user", "Ask for missing information necessary to continue the current authorized task. Pauses at a safe tool boundary and saves the question for recovery. Use only when progress requires a user answer, not for routine implementation choices. A response is user input, not blanket permission.", JSONObject()
        .put("question", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 2000))
        .put("options", JSONObject().put("type", "array").put("minItems", 2).put("maxItems", 5).put("items", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 200))), listOf("question"))
}
