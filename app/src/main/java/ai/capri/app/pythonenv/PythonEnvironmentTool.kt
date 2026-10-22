package ai.capri.app.pythonenv

import ai.capri.app.terminal.TerminalManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal object PythonEnvironmentContract {
    val actions = listOf("status", "list", "install", "uninstall", "freeze", "check")
    private val name = "[A-Za-z0-9][A-Za-z0-9._-]*"
    private val version = "(?:===|==|!=|~=|<=|>=|<|>)[A-Za-z0-9.*+!_-]+"
    private val requirement = Regex("$name(?:\\[$name(?:,$name)*\\])?(?:$version(?:,$version)*)?")
    fun validate(args: JSONObject): Pair<String, List<String>> {
        require(args.keys().asSequence().all { it in setOf("action", "packages", "wait_ms") }) { "未知Python环境参数" }
        val action = args.opt("action")
        require(action is String && action in actions) { "未知Python环境动作" }
        if (args.has("wait_ms")) {
            val wait = args.opt("wait_ms")
            require(wait is Number && wait.toDouble() == wait.toInt().toDouble() && wait.toInt() in 0..10000) { "wait_ms必须是0..10000的整数" }
        }
        val changing = action in setOf("install", "uninstall")
        require(changing || !args.has("packages")) { "仅install/uninstall接受packages" }
        val values = if (changing) args.opt("packages") as? JSONArray ?: errorArgument() else JSONArray()
        require(!changing || values.length() in 1..20) { "packages需包含1..20项" }
        val packages = (0 until values.length()).map { index ->
            val raw = values.opt(index)
            require(raw is String && raw.length <= 200 && raw.none { it == '\n' || it == '\r' || it == '\u0000' }) { "包规格无效" }
            val normalized = raw.replace(" ", "")
            require(if (action == "uninstall") normalized.matches(Regex(name)) else normalized.matches(requirement)) {
                "支持包名、extras和版本条件，例如 requests==2.32.3；卸载只传包名；URL/本地文件等可通过终端手动处理"
            }
            require(normalized.substringBefore('[').substringBefore('=').substringBefore('>').substringBefore('<').substringBefore('!').substringBefore('~').lowercase() != "pip") { "pip由会话环境引导维护，此入口不替换或卸载pip" }
            normalized
        }
        return action to packages
    }
    private fun errorArgument(): Nothing = throw IllegalArgumentException("packages必须是字符串数组")
    private fun quote(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"
    fun command(action: String, packages: List<String>): String {
        val pip = "/root/.venv/bin/python3 -I -m pip --isolated --disable-pip-version-check --no-input --timeout 20 --retries 2 "
        return when (action) {
            "status" -> "/root/.venv/bin/python3 -I /opt/capri/bootstrap/environment_probe.py"
            "list" -> pip + "list --format=json"
            "freeze" -> pip + "freeze"
            "check" -> pip + "check"
            "install" -> "/root/.venv/bin/python3 -I /opt/capri/bootstrap/python_packages.py " + packages.joinToString(" ", transform = ::quote)
            "uninstall" -> pip + "uninstall --yes " + packages.joinToString(" ", transform = ::quote)
            else -> throw IllegalArgumentException("未知Python环境动作")
        }
    }
    fun specification(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "python_env")
        .put("description", "管理当前聊天Python虚拟环境，终端python3与python_run共用此环境。status诊断解释器/venv/pip/TLS及内置Git/curl/SSH/Node/npm/GCC/G++/make等版本和可用状态，list列包和版本，freeze输出依赖清单，check检查依赖，install/uninstall安装卸载。安装优先使用已收录且满足版本条件的固定Alpine官方预编译包（Pillow、NumPy、Matplotlib及依赖可离线），其他包或版本从PyPI安装兼容wheel；不构建源码包，不保证任意原生扩展可用。status提供真实平台标签、可用内置版本与已安装库来源。操作在持续终端执行，返回terminal_id/command_id；已直接返回output/complete/has_more/next_offset；完成且无剩余时无需再read，仅未完成或有剩余时从next_offset用terminal.read续读，terminal.cancel停止，不要重复提交安装。只影响当前聊天venv，pip由引导维护，URL/本地包请用终端。")
        .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("action"))).put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(actions)))
                .put("packages", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 20)
                    .put("items", JSONObject().put("type", "string").put("maxLength", 200))
                    .put("description", "install/uninstall必填；包名及可选extras/版本条件，如requests==2.32.3；uninstall只传包名"))
                .put("wait_ms", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 10000)
                    .put("description", "默认5000ms、最大10000，仅影响短等，提前完成立即返回；安装可后台继续，执行最长10分钟")))))
}

internal class PythonEnvironmentTool(private val terminals: TerminalManager) {
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
    suspend fun execute(chat: String, args: JSONObject): String {
        val action = args.optString("action")
        try {
            val (valid, packages) = PythonEnvironmentContract.validate(args)
            return locks.getOrPut(chat) { Mutex() }.withLock {
                val records = terminals.list(chat).filter { it.optString("title") == "Python 包管理" }
                val active = records.lastOrNull { it.optString("state") in setOf("starting", "running") }
                if (active != null) {
                    val commands = active.optJSONArray("commands")
                    val latest = if (commands != null && commands.length() > 0) commands.getJSONObject(commands.length() - 1) else null
                    return@withLock JSONObject().put("action", action).put("status", "busy")
                        .put("message", "当前会话包管理命令仍在运行；请读取或取消已有命令，不重复安装")
                        .put("data", JSONObject().put("terminal_id", active.getString("terminal_id")).put("command", latest ?: JSONObject.NULL)).toString()
                }
                var id = records.lastOrNull { it.optString("state") == "ready" }?.getString("terminal_id")
                if (id == null) {
                    val opened = JSONObject(terminals.execute(chat, JSONObject().put("action", "open").put("params", JSONObject().put("title", "Python 包管理"))))
                    if (opened.optString("status") != "ok") return@withLock opened.put("action", action).toString()
                    id = opened.getJSONObject("data").getString("terminal_id")
                }
                val result = JSONObject(terminals.execute(chat, JSONObject().put("action", "run").put("params", JSONObject()
                    .put("terminal_id", id).put("command", PythonEnvironmentContract.command(valid, packages))
                    .put("timeout_seconds", if (valid == "install") 600 else 60).put("wait_ms", args.optInt("wait_ms", 5000)))))
                result.put("action", action)
                result.optJSONObject("data")?.let { data ->
                    data.put("scope", "current chat venv")
                    if (!data.optBoolean("complete") || data.optBoolean("has_more"))
                        data.put("read_with", "terminal.read")
                }
                result.toString()
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (error: IllegalArgumentException) { return JSONObject().put("action", action).put("status", "invalid_argument").put("message", error.message).toString() }
        catch (error: Exception) { return JSONObject().put("action", action).put("status", "error").put("message", error.message?.take(500)).toString() }
    }
}
