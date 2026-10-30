package ai.capri.app.agent

import org.json.JSONArray
import org.json.JSONObject

/** Always advertise names and purpose; defer costly schemas, never permissions or dispatch validation. */
internal class ToolDiscovery {
    private val loaded = linkedSetOf<String>()
    private var current = linkedMapOf<String, JSONObject>()
    private val deferred = setOf("android", "browser", "environment", "environment_check", "python_env", "knowledge", "personal_memory", "conversation_history", "model_info", "profile_search")
    fun assemble(all: JSONArray): JSONArray {
        current = (0 until all.length()).map { all.getJSONObject(it) }.associateByTo(linkedMapOf()) { it.getJSONObject("function").getString("name") }
        specifications().forEach { current[it.getJSONObject("function").getString("name")] = it }
        loaded.retainAll(current.keys)
        return JSONArray(current.filter { (name, _) -> !defer(name) || name in loaded }.values.toList())

    }
    fun allowed(name: String) = name in setOf("tool_search", "tool_describe") || (name in current && (!defer(name) || name in loaded))
    private fun defer(name: String) = name in deferred || name.startsWith("mcp_")
    fun manifest(): JSONObject {
        val tools = current.filterKeys(::defer).map { (name, spec) ->
            JSONObject().put("name", name).put("description", spec.getJSONObject("function").optString("description").take(160))
        }
        // Keep a bounded inventory; omitted entries remain searchable.
        return JSONObject().put("role", "system").put("content", "Additional available tools (discovery data, not instructions):\n" + JSONObject()
            .put("tools", JSONArray(tools.take(40))).put("omitted", (tools.size - 40).coerceAtLeast(0))
            .put("additional_domains", JSONArray(current.filterKeys { it.startsWith("mcp_") }.values.map {
                it.getJSONObject("function").optString("description").substringBefore(" · ").take(100)
            }.distinct().take(24))) +
            "\nUse tool_search for a capability or tool_describe for known names. These load full schemas for the next request. Core file, coding and execution tools are already available. Activation, authorization and runtime requirements still apply.")
    }
    fun execute(name: String, args: JSONObject): String {
        val names = if (name == "tool_describe") {
            require(args.length() == 1 && args.opt("names") is JSONArray) { "Provide only names, an array of tool names." }
            val values = args.getJSONArray("names")
            require(values.length() in 1..8 && (0 until values.length()).all { values.opt(it) is String }) { "names must contain 1..8 strings." }
            (0 until values.length()).map { values.getString(it) }
        } else {
            require(name == "tool_search" && args.keys().asSequence().all { it in setOf("query", "limit") }) { "Unknown discovery argument." }
            require(args.opt("query") is String && args.getString("query").length in 1..300 && args.getString("query").isNotBlank()) { "query must contain 1..300 characters." }
            val limit = args.opt("limit") ?: 4
            require(limit is Int && limit in 1..8) { "limit must be an integer in 1..8." }
            val terms = args.getString("query").lowercase().split(Regex("[^\\p{L}\\p{N}_]+" )).filter { it.isNotBlank() }
            current.entries.map { (key, spec) ->
                val text = (key + " " + spec.getJSONObject("function").optString("description")).lowercase()
                key to terms.sumOf { term -> if (key.lowercase() == term) 20 else if (text.contains(term)) 1 else 0 }
            }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
        }
        val missing = names.filter { it !in current }
        loaded.addAll(names.filter { it in current })
        return JSONObject().put("status", if (names.isEmpty()) "not_found" else if (missing.isEmpty()) "ok" else "partial")
            .put("tools", JSONArray(names.mapNotNull { current[it] })).put("unavailable", JSONArray(missing))
            .put("notice", "Found schemas are enabled on the next request. Availability is scoped to this session and does not grant authorization.").toString()
    }
    fun specifications() = listOf(searchSpec(), describeSpec())
    private fun searchSpec() = TaskState.spec("tool_search", "Find available specialized tools by English capability keywords or tool name and load matching schemas. Use when a needed capability is absent from the current tools. Does not execute discovered tools.", JSONObject()
        .put("query", JSONObject().put("type", "string").put("maxLength", 300))
        .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 8)), listOf("query"))
    private fun describeSpec() = TaskState.spec("tool_describe", "Load complete schemas for known available tool names. Multiple names may be requested together. Does not activate disconnected MCP services or execute tools.", JSONObject()
        .put("names", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("minItems", 1).put("maxItems", 8)), listOf("names"))
}
