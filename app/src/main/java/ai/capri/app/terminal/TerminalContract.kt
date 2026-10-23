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
        .put("description", "手机本地持续终端，按当前聊天隔离管理。open创建PTY Shell，list/attach恢复连接；run提交Shell命令，直接返回command_id、output、退出状态、complete/has_more/next_offset，cwd/export变量跨命令保留。完成且has_more=false时无需再read；运行中或剩余日志用返回的next_offset续读，勿从0重复。短Python用python_run，相关生成与基本验证尽量一次执行。run通常省略wait_ms，默认短等5000ms，提前结束立即返回；显式传入只能0..10000，不允许30000或按命令时长填写。执行时长用timeout_seconds；read默认不等，可设wait_ms为0..20000，有新完整字符、命令结束或终端关闭时提前返回。等待结束不会停止命令。run默认超时60秒，持续服务可设timeout_seconds，最大86400秒。read按字节offset增量读取，返回next_offset；command_id省略时读终端整体输出。input向当前命令输入文本（需要换行请带\\n），cancel中断命令，close关闭终端及关联进程，clear_history仅空闲时清理日志，delete删除已关闭终端历史。命令可在模型回复后持续运行，不要循环查询耗尽模型轮数；告知用户终端/命令ID供后续查询。停止聊天不等于关闭终端。参数直接放顶层，如{action:read,terminal_id:返回ID,command_id:返回ID,offset:上次next_offset,wait_ms:10000}，不嵌套params。Shell为POSIX /bin/sh（dash），不支持source；python3已指向会话venv，无需激活。需要Bash语法时显式bash -c。不提供Root或强安全隔离。")
        .put("parameters", schema()))

    private fun schema(): JSONObject {
        val branches = actions.map { action ->
            val branch = parameterSchema(action)
            branch.getJSONObject("properties").put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf(action))))
            branch.getJSONArray("required").put("action")
            branch
        }
        // Concrete top-level field types remain visible even to providers ignoring oneOf.
        val properties = JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(actions)))
        branches.forEach { branch -> branch.getJSONObject("properties").let { fields -> fields.keys().asSequence().filter { it != "action" }.forEach { properties.put(it, fields.get(it)) } } }
        properties.getJSONObject("wait_ms").put("description", "run默认5000，范围0..10000且完成即返回；read默认0，范围0..20000，运行中的命令建议10000。短等待与执行超时独立。")
        return JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("action"))).put("properties", properties).put("oneOf", JSONArray(branches))
    }

    private fun parameterSchema(action: String): JSONObject {
        fun string(max: Int, description: String) = JSONObject().put("type", "string").put("maxLength", max).put("description", description)
        fun integer(min: Int, max: Int, description: String) = JSONObject().put("type", "integer").put("minimum", min).put("maximum", max).put("description", description)
        fun id(description: String) = string(36, description)
            .put("pattern", "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val available = JSONObject()
            .put("terminal_id", id("使用工具返回的当前聊天终端UUID，原样复制，不自行编造"))
            .put("command_id", id("使用工具返回的命令UUID；read省略时读终端整体输出"))
            .put("title", string(80, "终端名称"))
            .put("command", string(128000, "Shell脚本；需要持续Shell状态/后台/交互时使用。短Python优先python_run。"))
            .put("timeout_seconds", integer(1, 86400, "执行超时，默认60秒；与等待返回时间独立"))
            .put("wait_ms", integer(0, if (action == "run") 10000 else 20000,
                if (action == "run") "通常省略；默认5000，最大10000，禁止30000。执行超时用timeout_seconds，等待超时不停止命令"
                else "等待新输出或结束，默认0，最大20000ms；有结果提前返回"))
            .put("offset", integer(0, 2000000, "默认0；续读用上次next_offset，避免重复读取"))
            .put("limit", integer(4, 32000, "默认16000字节"))
            .put("text", string(4096, "最多4096 UTF-8字节；需要换行时包含真实换行"))
        val properties = JSONObject()
        fields.getValue(action).forEach { properties.put(it, available.get(it)) }
        val required = when (action) {
            "open", "list" -> emptyList()
            "run" -> listOf("terminal_id", "command")
            "input" -> listOf("terminal_id", "command_id", "text")
            "cancel" -> listOf("terminal_id", "command_id")
            else -> listOf("terminal_id")
        }
        return JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", properties).put("required", JSONArray(required))
    }



    fun validate(args: JSONObject): Pair<String, JSONObject> {
        val action = text(args, "action", 32)
        require(action in actions) { "未知终端动作" }
        require(!args.has("params") || args.opt("params") is JSONObject) { "params必须为对象" }
        val top = args.keys().asSequence().filter { it != "action" && it != "params" }.toList()
        require(!args.has("params") || top.isEmpty()) { "不要混用顶层参数和旧params；请将所有动作参数直接放顶层" }
        val params = if (args.has("params")) args.getJSONObject("params") else JSONObject().apply { top.forEach { put(it, args.get(it)) } }
        require(params.keys().asSequence().all { it in fields.getValue(action) }) { "未知${action}参数；允许：${fields.getValue(action).sorted().joinToString()}。动作参数直接放顶层" }
        if (action !in setOf("open", "list")) id(params, "terminal_id")
        if (params.has("command_id")) id(params, "command_id")
        if (action in setOf("input", "cancel")) id(params, "command_id")
        if (action == "run") {
            text(params, "command", 128_000)
            integer(params, "timeout_seconds", 60, 1, 86_400)
            try { integer(params, "wait_ms", DEFAULT_RUN_WAIT_MS, 0, 10_000) }
            catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("run.wait_ms只能是0..10000的整数；建议省略此字段，默认5000ms。命令执行时长用timeout_seconds设置，长命令返回后用read续读。本次未提交命令，请修正后再调用。")
            }
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
