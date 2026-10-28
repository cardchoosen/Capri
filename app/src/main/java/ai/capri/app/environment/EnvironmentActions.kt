package ai.capri.app.environment

import org.json.JSONArray
import org.json.JSONObject

internal object EnvironmentActions {
    fun validate(args: JSONObject): JSONObject {
        val action = args.getString("action")
        val fields = when (action) {
            "lookup", "document" -> setOf("action", "query", "scope", "offset")
            "record" -> setOf("action", "key", "title", "conclusion", "source_receipt", "excerpt", "conditions", "validity", "ttl_seconds")
            else -> error("未知档案动作")
        }
        require(args.keys().asSequence().all { it in fields }) { "未知档案参数" }
        fun text(key: String, max: Int, required: Boolean = false, multiline: Boolean = false) {
            if (!args.has(key)) { require(!required) { "缺少$key" }; return }
            val value = args.opt(key)
            require(value is String && value.length <= max && (!required || value.isNotBlank()) && '\u0000' !in value) { "$key 字段无效" }
            if (!multiline) EnvironmentArchive.safe(value, max)
        }
        if (action == "record") {
            text("key", 160, true); text("title", 160, true); text("conclusion", 1600, true)
            text("source_receipt", 100, true); text("excerpt", 2000, true, true); text("conditions", 600)
            require(args.optString("validity", "boot") in setOf("stable", "boot", "environment", "volatile"))
            if (args.has("ttl_seconds")) {
                val value = args.opt("ttl_seconds")
                require(value is Number && value.toDouble() == value.toLong().toDouble() && value.toLong() in 1..2592000)
            }
        } else {
            text("query", 300); text("scope", 160)
            if (args.has("offset")) {
                val offset = args.opt("offset")
                require(offset is Number && offset.toDouble() == offset.toInt().toDouble() && offset.toInt() >= 0)
            }
        }
        return JSONObject(args.toString())
    }
    fun extend(spec: JSONObject) {
        val properties = spec.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        fun field(name: String, description: String) { properties.put(name, JSONObject().put("type", "string").put("description", description)) }
        field("query", "lookup/document的查询关键词，省略返回全部；lookup按条目分页，document按字符分页")
        field("scope", "lookup/document可选执行环境过滤；record的环境由凭证决定，不能自行改写")
        field("key", "record条目标识，主题不限，如hardware.wifi.driver")
        field("title", "record简短标题")
        field("conclusion", "record简洁结论，明确未知/限制，不能含凭据")
        field("source_receipt", "record引用真实工具返回的environment_receipt")
        field("excerpt", "record所引用工具结果的原文片段，最长2000字符")
        field("conditions", "record的适用条件、仍未确定部分或已知失效条件")
        properties.put("validity", JSONObject().put("type", "string").put("enum", JSONArray(listOf("stable", "boot", "environment", "volatile"))))
        properties.put("ttl_seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 2592000))
        properties.put("offset", JSONObject().put("type", "integer").put("minimum", 0))
    }
}
