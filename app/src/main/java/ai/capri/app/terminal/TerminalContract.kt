package ai.capri.app.terminal

import org.json.JSONArray
import org.json.JSONObject

/** A closed protocol. IDs are always resolved against the caller's chat, never supplied paths/PIDs. */
internal object TerminalContract {
    val actions = listOf("open", "list", "attach", "run", "read", "input", "cancel", "close", "clear_history", "delete")
    private val fields = mapOf(
        "open" to setOf("title"), "list" to emptySet(),
        "attach" to setOf("terminal_id"), "run" to setOf("terminal_id", "command", "timeout_seconds", "wait_ms"),
        "read" to setOf("terminal_id", "command_id", "offset", "limit", "wait_ms"),
        "input" to setOf("terminal_id", "command_id", "text"), "cancel" to setOf("terminal_id", "command_id"),
        "close" to setOf("terminal_id"), "clear_history" to setOf("terminal_id"), "delete" to setOf("terminal_id"))
    fun specification(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "terminal")
        .put("description", "手机本地持续终端，按当前聊天隔离管理。open创建PTY Shell，list/attach恢复连接；run提交Shell命令并返回command_id，cwd/export变量跨命令保留。run默认短等1000ms，可设wait_ms为0..10000；read默认不等，可设wait_ms为0..20000，有新完整字符、命令结束或终端关闭时提前返回。等待结束不会停止命令。run默认超时60秒，持续服务可设timeout_seconds，最大86400秒。read按字节offset增量读取，返回next_offset；command_id省略时读终端整体输出。input向当前命令输入文本（需要换行请带\\n），cancel中断命令，close关闭终端及关联进程，clear_history仅空闲时清理日志，delete删除已关闭终端历史。命令可在模型回复后持续运行，不要循环查询耗尽模型轮数；告知用户终端/命令ID供后续查询。停止聊天不等于关闭终端。需要先初始化Python环境；不提供Root或强安全隔离。")
        .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("action"))).put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(actions)))
                .put("params", parameterSchema()))))

    private fun parameterSchema(): JSONObject {
        fun string(max: Int, description: String) = JSONObject().put("type", "string").put("maxLength", max).put("description", description)
        fun integer(min: Int, max: Int, description: String) = JSONObject().put("type", "integer").put("minimum", min).put("maximum", max).put("description", description)
        val properties = JSONObject()
            .put("terminal_id", string(36, "除open/list外必填，使用当前聊天已有的终端UUID"))
            .put("command_id", string(36, "input/cancel必填；read可选，省略读取整个终端"))
            .put("title", string(80, "open可选终端名称"))
            .put("command", string(128000, "run必填Shell脚本；检查既有终端/服务后复用，避免重复启动。主任务与可选诊断分开执行。"))
            .put("timeout_seconds", integer(1, 86400, "仅run，默认60；命令执行超时，与wait_ms独立"))
            .put("wait_ms", integer(0, 20000, "仅run/read。run默认1000、最大10000；read默认0、最大20000。有结果提前返回；不停止命令。"))
            .put("offset", integer(0, 2000000, "仅read，默认0；后续使用next_offset"))
            .put("limit", integer(4, 32000, "仅read，默认16000字节"))
            .put("text", string(4096, "仅input必填，最多4096 UTF-8字节；需要换行时包含真实换行"))
        return JSONObject().put("type", "object").put("additionalProperties", false).put("properties", properties)
            .put("description", "按action只传所需字段。open可选title；list不传参数；run需要terminal_id/command，wait_ms最大10000；read需要terminal_id，wait_ms最大20000；input需要terminal_id/command_id/text；cancel需要terminal_id/command_id；其余需要terminal_id。")
    }


    fun validate(args: JSONObject): Pair<String, JSONObject> {
        require(args.keys().asSequence().all { it in setOf("action", "params") }) { "未知终端参数" }
        val action = text(args, "action", 32)
        require(action in actions) { "未知终端动作" }
        require(!args.has("params") || args.opt("params") is JSONObject) { "params必须为对象" }
        val params = args.optJSONObject("params") ?: JSONObject()
        require(params.keys().asSequence().all { it in fields.getValue(action) }) { "未知动作参数" }
        if (action !in setOf("open", "list")) id(params, "terminal_id")
        if (params.has("command_id")) id(params, "command_id")
        if (action in setOf("input", "cancel")) id(params, "command_id")
        if (action == "run") {
            text(params, "command", 128_000)
            integer(params, "timeout_seconds", 60, 1, 86_400)
            integer(params, "wait_ms", 1000, 0, 10_000)
        }
        if (action == "input") require(text(params, "text", 4096, blankAllowed = true).toByteArray().size <= 4096) { "输入最多4096字节" }
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
        require(value is String && value.length <= max && '\u0000' !in value && (blankAllowed || value.isNotBlank())) { "$key 文本无效" }
        return value
    }
    fun id(args: JSONObject, key: String): String = text(args, key, 36).also {
        require(it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "$key 无效" }
    }
    fun integer(args: JSONObject, key: String, default: Int, min: Int, max: Int): Int {
        if (!args.has(key)) return default
        val number = args.opt(key)
        require(number is Number && number.toDouble().isFinite() && number.toDouble() == number.toLong().toDouble() && number.toLong() in min.toLong()..max.toLong()) { "$key 超出范围 $min..$max" }
        return number.toInt()
    }
}
