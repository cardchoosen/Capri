package ai.capri.app.environment

import org.json.JSONArray
import org.json.JSONObject

internal object EnvironmentActions {
    val fields = linkedMapOf(
        "lookup" to setOf("query", "scope", "offset"), "document" to setOf("query", "scope", "offset"),
        "record" to setOf("key", "title", "conclusion", "source_receipt", "excerpt", "conditions", "validity", "ttl_seconds"))
    fun required(action: String) = if (action == "record") listOf("key", "title", "conclusion", "source_receipt", "excerpt") else emptyList()
    fun validate(args: JSONObject): JSONObject {
        val action = args.getString("action")
        val allowed = fields[action] ?: error("Unknown environment archive action.")
        require(args.keys().asSequence().all { it == "action" || it in allowed }) { "Unknown environment archive argument." }
        fun text(key: String, max: Int, required: Boolean = false, multiline: Boolean = false) {
            if (!args.has(key)) { require(!required) { "Missing required field: $key." }; return }
            val value = args.opt(key)
            require(value is String && value.length <= max && (!required || value.isNotBlank()) && '\u0000' !in value) { "$key must be text within its size limit." }
            if (!multiline) EnvironmentArchive.safe(value, max)
        }
        if (action == "record") {
            text("key", 160, true); text("title", 160, true); text("conclusion", 1600, true)
            text("source_receipt", 100, true); text("excerpt", 2000, true, true); text("conditions", 600)
            require(!args.has("validity") || args.opt("validity") is String) { "validity must be a string." }
            require(args.optString("validity", "boot") in setOf("stable", "boot", "environment", "volatile")) { "Unknown validity policy." }
            if (args.has("ttl_seconds")) {
                val value = args.opt("ttl_seconds")
                val max = if (args.optString("validity") == "volatile") 300L else 2592000L
                require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in 1..max) { "ttl_seconds must be an integer in 1..$max." }
            }
        } else {
            text("query", 300); text("scope", 160)
            if (args.has("offset")) {
                val offset = args.opt("offset")
                require(offset is Number && offset.toDouble().isFinite() && offset.toDouble() == offset.toLong().toDouble() && offset.toLong() in 0..Int.MAX_VALUE.toLong()) { "offset must be a nonnegative 32-bit integer." }
            }
        }
        return JSONObject(args.toString())
    }
    fun properties(): JSONObject {
        val properties = JSONObject()
        fun field(name: String, max: Int, description: String) { properties.put(name, JSONObject().put("type", "string").put("maxLength", max).put("description", description)) }
        field("query", 300, "Optional archive keywords; omit for all entries.")
        field("scope", 160, "Optional lookup/document scope filter. Record scope is fixed by its execution receipt.")
        field("key", 160, "Record topic identifier, such as hardware.wifi.driver.")
        field("title", 160, "Short record title.")
        field("conclusion", 1600, "Concise conclusion with explicit limits and unknowns; no credentials.")
        field("source_receipt", 100, "An environment_receipt returned by an actual tool invocation in this process.")
        field("excerpt", 2000, "Exact excerpt from that tool result; required as evidence.")
        field("conditions", 600, "Applicability, unresolved questions, or invalidation conditions.")
        properties.put("validity", JSONObject().put("type", "string").put("enum", JSONArray(listOf("stable", "boot", "environment", "volatile"))).put("description", "Default boot; volatile records allow at most 300 seconds."))
        properties.put("ttl_seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 2592000))
        properties.put("offset", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", Int.MAX_VALUE).put("description", "Continue from next_offset; lookup counts entries, document counts characters."))
        return properties
    }
}
