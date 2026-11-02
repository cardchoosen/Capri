package ai.capri.app.agent

import ai.capri.app.model.ContextTokens
import org.json.JSONArray
import org.json.JSONObject

/** One immutable contract snapshot shared by discovery, catalog and pre-dispatch checks. */
internal class ToolRegistry(specifications: JSONArray) {
    class Definition(val name: String, contract: JSONObject) {
        private val encoded = contract.toString()
        val contract get() = JSONObject(encoded)
        val function get() = contract.getJSONObject("function")
        val description get() = function.optString("description")
        val deferred get() = name in specialized || name.startsWith("mcp_")
        val tokens = ContextTokens.text(encoded)
        fun metadata() = JSONObject().put("name", name).put("description", description.take(160))
            .put("availability", "callable").put("reason", "runtime_checks_required")
            .put("exposure", if (deferred) "on_demand" else "core")
    }
    val definitions: Map<String, Definition>
    init {
        val values = linkedMapOf<String, Definition>()
        for (i in 0 until specifications.length()) {
            val contract = JSONObject(specifications.getJSONObject(i).toString())
            val f = contract.getJSONObject("function"); val name = f.getString("name")
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) && f.opt("parameters") is JSONObject) { "Invalid tool contract." }
            require(name !in values || values.getValue(name).contract.toString() == contract.toString()) { "Conflicting tool contracts: $name." }
            values[name] = Definition(name, contract)
        }
        definitions = values
    }
    fun validate(name: String, args: JSONObject) {
        val definition = definitions[name] ?: error("Tool is unavailable; refresh capability discovery.")
        // Preserve imported MCP schema semantics; the existing transport/server remains authoritative.
        if (name.startsWith("mcp_")) require(args.toString().length <= 128_000) { "MCP arguments exceed 128000 characters." }
        else ToolInput.check(args, definition.function.getJSONObject("parameters"))
    }
    companion object {
        val specialized = setOf("task_experience", "coding_diagnostics", "coding_reference", "coding_restore", "environment", "environment_check", "python_env", "knowledge", "personal_memory", "conversation_history", "model_info", "profile_search")
        val discovery = setOf("tool_search", "tool_describe", "tool_invoke")
        /** Used for evidence and progress accounting; never grants permission or dispatches a call. */
        fun function(call: JSONObject): JSONObject {
            val f = call.getJSONObject("function")
            if (f.optString("name") != "tool_invoke") return f
            val args = JSONObject(f.getString("arguments"))
            require(args.keys().asSequence().toSet() == setOf("name", "arguments") && args.opt("name") is String && args.opt("arguments") is JSONObject) { "tool_invoke requires only name and object arguments." }
            val name = args.getString("name")
            require(name !in discovery && (name in specialized || name.startsWith("mcp_"))) { "Bridge target must be a specialized tool; nested or control invocation is forbidden." }
            return JSONObject().put("name", name).put("arguments", args.getJSONObject("arguments").toString())
        }
    }
}

/** Common JSON Schema shape checks. Tool-specific validators and authorization remain authoritative. */
internal object ToolInput {
    fun check(value: Any?, schema: JSONObject, root: JSONObject = schema, depth: Int = 0) {
        require(depth <= 48) { "Tool input/schema nesting limit exceeded." }
        schema.optString("\$ref").takeIf { it.isNotBlank() }?.let { ref ->
            require(ref.startsWith("#/")) { "External schema references require tool-specific validation." }
            var target: Any = root
            ref.drop(2).split('/').forEach { target = (target as? JSONObject)?.get(it.replace("~1", "/").replace("~0", "~")) ?: error("Invalid schema reference.") }
            check(value, target as JSONObject, root, depth + 1)
        }
        fun accepts(branch: JSONObject) = runCatching { check(value, branch, root, depth + 1) }.isSuccess
        for (kind in listOf("oneOf", "anyOf", "allOf")) schema.optJSONArray(kind)?.let { a ->
            val matches = (0 until a.length()).count { accepts(a.getJSONObject(it)) }
            require(when(kind) { "oneOf" -> matches == 1; "anyOf" -> matches > 0; else -> matches == a.length() }) { "Tool arguments do not match $kind." }
        }
        schema.optJSONArray("enum")?.let { a -> require((0 until a.length()).any { a.opt(it) == value }) { "Invalid tool argument enum value." } }
        if (schema.has("const")) require(value == schema.get("const")) { "Invalid tool argument constant." }
        fun matches(type: String) = when(type) {
            "object" -> value is JSONObject; "array" -> value is JSONArray; "string" -> value is String
            "boolean" -> value is Boolean; "number" -> value is Number
            "integer" -> value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0
            "null" -> value == null || value == JSONObject.NULL; else -> true
        }
        val type = schema.opt("type")
        require(when(type) { is String -> matches(type); is JSONArray -> (0 until type.length()).any { matches(type.getString(it)) }; else -> true }) { "Invalid tool argument type." }
        if (value is JSONObject) {
            schema.optJSONArray("required")?.let { a -> for(i in 0 until a.length()) require(value.has(a.getString(i))) { "Missing required tool argument: ${a.getString(i)}." } }
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            for(key in value.keys()) {
                val field = properties.optJSONObject(key)
                if (field != null) check(value.get(key), field, root, depth + 1)
                else { require(schema.opt("additionalProperties") != false) { "Unknown tool argument: $key." }; schema.optJSONObject("additionalProperties")?.let { check(value.get(key),it,root,depth+1) } }
            }
        }
        if (value is JSONArray) {
            require(value.length() >= schema.optInt("minItems",0) && value.length() <= schema.optInt("maxItems",Int.MAX_VALUE)) { "Tool array length exceeds its contract." }
            schema.optJSONObject("items")?.let { for(i in 0 until value.length()) check(value.get(i),it,root,depth+1) }
        }
        if (value is String) require(value.codePointCount(0,value.length) >= schema.optInt("minLength",0) && value.codePointCount(0,value.length) <= schema.optInt("maxLength",Int.MAX_VALUE)) { "Tool text length exceeds its contract." }
        if (value is Number) require(value.toDouble().isFinite() && value.toDouble() >= schema.optDouble("minimum",Double.NEGATIVE_INFINITY) && value.toDouble() <= schema.optDouble("maximum",Double.POSITIVE_INFINITY)) { "Tool number exceeds its contract." }
    }
}
