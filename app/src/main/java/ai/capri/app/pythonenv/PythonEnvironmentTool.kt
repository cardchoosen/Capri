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
        require(args.keys().asSequence().all { it in setOf("action", "packages", "wait_ms") }) { "Unknown Python environment argument." }
        val action = args.opt("action")
        require(action is String && action in actions) { "Unknown Python environment action." }
        if (args.has("wait_ms")) {
            val wait = args.opt("wait_ms")
            require(wait is Number && wait.toDouble() == wait.toInt().toDouble() && wait.toInt() in 0..10000) { "wait_ms must be an integer in 0..10000." }
        }
        val changing = action in setOf("install", "uninstall")
        require(changing || !args.has("packages")) { "Only install/uninstall accept packages." }
        val values = if (changing) args.opt("packages") as? JSONArray ?: errorArgument() else JSONArray()
        require(!changing || values.length() in 1..20) { "packages must contain 1..20 items." }
        val packages = (0 until values.length()).map { index ->
            val raw = values.opt(index)
            require(raw is String && raw.length <= 200 && raw.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Invalid package requirement." }
            val normalized = raw.replace(" ", "")
            require(if (action == "uninstall") normalized.matches(Regex(name)) else normalized.matches(requirement)) {
                "Use distribution names, extras and version constraints, such as requests==2.32.3; uninstall accepts names only. URLs/local sources require explicit terminal handling."
            }
            require(normalized.substringBefore('[').substringBefore('=').substringBefore('>').substringBefore('<').substringBefore('!').substringBefore('~').lowercase() != "pip") { "pip is bootstrap-managed; this tool cannot replace or uninstall it." }
            normalized
        }
        return action to packages
    }
    private fun errorArgument(): Nothing = throw IllegalArgumentException("packages must be a string array.")
    private fun quote(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"
    fun command(action: String, packages: List<String>): String {
        val pip = "/root/.venv/bin/python3 -I -m pip --isolated --disable-pip-version-check --no-input --timeout 20 --retries 2 "
        return when (action) {
            "status" -> "/root/.venv/bin/python3 -I /opt/capri/bootstrap/environment_probe.py"
            "list" -> pip + "list --format=json"
            "freeze" -> pip + "freeze"
            "check" -> pip + "check"
            "install" -> pip + "install --prefer-binary --cache-dir /opt/capri/cache/pip " + packages.joinToString(" ", transform = ::quote)
            "uninstall" -> pip + "uninstall --yes " + packages.joinToString(" ", transform = ::quote)
            else -> throw IllegalArgumentException("Unknown Python environment action.")
        }
    }
    fun specification(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "python_env")
        .put("description", "Legacy interface for the chat venv using normal PyPI and shared download caches. Prepare build dependencies for source distributions. Prefer environment for new tasks.")
        .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("action"))).put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(actions)))
                .put("packages", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 20)
                    .put("items", JSONObject().put("type", "string").put("maxLength", 200))
                    .put("description", "Required for install/uninstall; names with optional extras/version constraints. Uninstall accepts names only."))
                .put("wait_ms", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 10000)
                    .put("description", "Response wait: default 5000 ms, max 10000; returns early on completion. Installation may continue for up to 10 minutes.")))))
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
                        .put("message", "A package-management command is running in this chat. Read or cancel it instead of installing again.")
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
