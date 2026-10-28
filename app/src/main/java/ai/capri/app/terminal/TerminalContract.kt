package ai.capri.app.terminal

import org.json.JSONArray
import org.json.JSONObject

/** A closed protocol. IDs are always resolved against the caller's chat, never supplied paths/PIDs. */
internal object TerminalContract {
    const val DEFAULT_RUN_WAIT_MS = 5000
    val actions = listOf("open", "list", "attach", "run", "read", "input", "cancel", "close", "clear_history", "delete")
    private val fields = mapOf(
        "open" to setOf("title"), "list" to emptySet(),
        "attach" to setOf("terminal_id"), "run" to setOf("terminal_id", "command", "timeout_seconds", "wait_ms"),
        "read" to setOf("terminal_id", "command_id", "offset", "limit", "wait_ms"),
        "input" to setOf("terminal_id", "command_id", "text"), "cancel" to setOf("terminal_id", "command_id"),
        "close" to setOf("terminal_id"), "clear_history" to setOf("terminal_id"), "delete" to setOf("terminal_id"))
    fun specification(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "terminal")
        .put("description", "Persistent, chat-scoped PTY shell. open creates; list/attach recover existing IDs. run returns bounded output, command state, complete/has_more and next_offset. Resume running/unread output with read and next_offset; completed commands need no extra read. input sends text, cancel interrupts one command, close stops the terminal and its children, clear_history requires idle, delete requires closed. Shell cwd/exports persist; commands can outlive a reply. Stopping chat does not close terminals. Use python_run for short tasks. POSIX /bin/sh; use bash -c explicitly when needed. Python already uses the chat venv. No host Root or strong sandbox is provided. Arguments are flat, not nested in params.")
        .put("parameters", schema()))

    private fun required(action: String) = when (action) {
        "open", "list" -> emptyList()
        "run" -> listOf("terminal_id", "command")
        "input" -> listOf("terminal_id", "command_id", "text")
        "cancel" -> listOf("terminal_id", "command_id")
        else -> listOf("terminal_id")
    }
    private fun schema(): JSONObject {
        fun string(max: Int, description: String) = JSONObject().put("type", "string").put("maxLength", max).put("description", description)
        fun integer(min: Int, max: Int, description: String) = JSONObject().put("type", "integer").put("minimum", min).put("maximum", max).put("description", description)
        fun id(description: String) = string(36, description).put("pattern", "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val properties = JSONObject()
            .put("terminal_id", id("Copy a terminal UUID returned for the current chat."))
            .put("command_id", id("Copy a returned command UUID; omit on read for aggregate terminal output."))
            .put("title", string(80, "Optional terminal title."))
            .put("command", string(128000, "Nonempty shell script; use for persistent state, interaction or background work."))
            .put("timeout_seconds", integer(1, 86400, "Execution deadline, default 60 seconds; independent of response waiting."))
            .put("wait_ms", integer(0, 20000, "run: omit for 5000 ms, max 10000. read: default 0, max 20000; use 10000 for running commands. run returns early on completion; read returns on output/completion. Waiting never stops a command."))
            .put("offset", integer(0, 2000000, "Byte offset, default 0; continue with next_offset to avoid replaying output."))
            .put("limit", integer(4, 32000, "Maximum output bytes, default 16000; UTF-8 boundaries are preserved."))
            .put("text", string(4096, "Input text, at most 4096 UTF-8 bytes; include a real newline to submit a line."))
        return ai.capri.app.ActionSchema.build(properties, fields, ::required) { action ->
            if (action == "run") mapOf("wait_ms" to JSONObject().put("maximum", 10000)) else emptyMap()
        }
    }

    fun validate(args: JSONObject): Pair<String, JSONObject> {
        val action = text(args, "action", 32)
        require(action in actions) { "Unknown terminal action." }
        require(!args.has("params") || args.opt("params") is JSONObject) { "Legacy params must be an object." }
        val top = args.keys().asSequence().filter { it != "action" && it != "params" }.toList()
        require(!args.has("params") || top.isEmpty()) { "Do not mix flat arguments with legacy params. Put action arguments at the top level." }
        val params = if (args.has("params")) args.getJSONObject("params") else JSONObject().apply { top.forEach { put(it, args.get(it)) } }
        require(params.keys().asSequence().all { it in fields.getValue(action) }) { "Unknown $action argument. Allowed: ${fields.getValue(action).sorted().joinToString()}. Use flat arguments." }
        if (action !in setOf("open", "list")) id(params, "terminal_id")
        if (params.has("command_id")) id(params, "command_id")
        if (action in setOf("input", "cancel")) id(params, "command_id")
        if (action == "run") {
            text(params, "command", 128_000)
            integer(params, "timeout_seconds", 60, 1, 86_400)
            try { integer(params, "wait_ms", DEFAULT_RUN_WAIT_MS, 0, 10_000) }
            catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("run.wait_ms must be an integer in 0..10000; omit for 5000 ms. Set timeout_seconds for execution duration, then read using next_offset. No command was submitted.")
            }
        }
        if (action == "input") require(text(params, "text", 4096, blankAllowed = true).toByteArray().size <= 4096) { "Input exceeds 4096 UTF-8 bytes." }
        if (action == "read") {
            integer(params, "offset", 0, 0, 2_000_000)
            integer(params, "limit", 16_000, 4, 32_000)
            integer(params, "wait_ms", 0, 0, 20_000)
        }
        if (params.has("title")) text(params, "title", 80)
        return action to params
    }
    fun text(args: JSONObject, key: String, max: Int, blankAllowed: Boolean = false): String {
        val value = args.opt(key)
        require(value is String && value.length <= max && '\u0000' !in value && (blankAllowed || value.isNotBlank())) { "$key must be valid nonempty text within its size limit." }
        return value
    }
    fun id(args: JSONObject, key: String): String = text(args, key, 36).also {
        require(it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "$key must be a returned UUID." }
    }
    fun integer(args: JSONObject, key: String, default: Int, min: Int, max: Int): Int {
        if (!args.has(key)) return default
        val number = args.opt(key)
        require(number is Number && number.toDouble().isFinite() && number.toDouble() == number.toLong().toDouble() && number.toLong() in min.toLong()..max.toLong()) { "$key must be an integer in $min..$max." }
        return number.toInt()
    }
}
