package ai.capri.app.terminal

import org.json.JSONArray
import org.json.JSONObject

/** A closed protocol. IDs are always resolved against the caller's chat, never supplied paths/PIDs. */
internal object TerminalContract {
    val actions = listOf("open", "list", "attach", "run", "read", "input", "cancel", "close", "clear_history", "delete")
    private val fields = mapOf(
        "open" to setOf("title"), "list" to emptySet(),
        "attach" to setOf("terminal_id"), "run" to setOf("terminal_id", "command", "timeout_seconds", "wait_ms"),
        "read" to setOf("terminal_id", "command_id", "offset", "limit"),
        "input" to setOf("terminal_id", "command_id", "text"), "cancel" to setOf("terminal_id", "command_id"),
        "close" to setOf("terminal_id"), "clear_history" to setOf("terminal_id"), "delete" to setOf("terminal_id"))
    fun specification(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "terminal")
        .put("description", "手机本地持续终端，按当前聊天隔离管理。open创建PTY Shell，list/attach恢复连接；run提交Shell命令并返回command_id，cwd/export变量跨命令保留。run默认超时60秒，持续服务可设timeout_seconds，最大86400秒。read按字节offset增量读取，返回next_offset；command_id省略时读终端整体输出。input向当前命令输入文本（需要换行请带\\n），cancel中断命令，close关闭终端及关联进程，clear_history仅空闲时清理日志，delete删除已关闭终端历史。命令可在模型回复后持续运行，不要循环查询耗尽模型轮数；告知用户终端/命令ID供后续查询。停止聊天不等于关闭终端。需要先初始化Python环境；不提供Root或强安全隔离。")
        .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("action"))).put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(actions)))
                .put("params", JSONObject().put("type", "object").put("description", "action参数：open可选title；run需要terminal_id/command，可选timeout_seconds(1..86400)/wait_ms(0..2000)；read需要terminal_id，可选command_id/offset(默认0)/limit(4..32000，默认16000)；input需要terminal_id/command_id/text；cancel需要terminal_id/command_id；attach/close/clear_history/delete需要terminal_id。")))))

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
            integer(params, "wait_ms", 250, 0, 2000)
        }
        if (action == "input") require(text(params, "text", 4096, blankAllowed = true).toByteArray().size <= 4096) { "输入最多4096字节" }
        if (action == "read") {
            integer(params, "offset", 0, 0, 2_000_000)
            integer(params, "limit", 16_000, 4, 32_000)
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
