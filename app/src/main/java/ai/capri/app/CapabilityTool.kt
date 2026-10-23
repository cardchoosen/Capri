package ai.capri.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal object CapabilityContract {
    private val fields = setOf("action", "requirements", "from_project", "command", "timeout_seconds")
    private val requirementFields = setOf("commands", "python_packages", "imports", "system_packages", "headers", "libraries", "compiler")
    fun validate(args: JSONObject): JSONObject {
        require(args.keys().asSequence().all { it in fields }) { "未知环境参数" }
        val action = args.optString("action", "inspect")
        require(action in listOf("inspect", "prepare", "verify")) { "action需为inspect/prepare/verify" }
        if (args.has("from_project")) require(action == "prepare" && args.opt("from_project") is Boolean) { "from_project仅供prepare使用且需为布尔值" }
        val requirements = if (args.has("requirements")) args.opt("requirements") as? JSONObject ?: throw IllegalArgumentException("requirements需为对象") else JSONObject()
        require(requirements.keys().asSequence().all { it in requirementFields }) { "未知依赖类型" }
        for (key in requirementFields - "compiler") {
            if (!requirements.has(key)) continue
            val list = requirements.opt(key) as? JSONArray ?: throw IllegalArgumentException("$key 需为数组")
            require(list.length() in 0..20) { "$key 最多20项" }
            for (i in 0 until list.length()) {
                val item = list.opt(i)
                require(item is String && item.length in 1..200) { "$key 项目无效" }
                when (key) {
                    "python_packages" -> ai.capri.app.pythonenv.PythonEnvironmentContract.validate(JSONObject().put("action", "install").put("packages", JSONArray(listOf(item))))
                    "headers" -> require(item.matches(Regex("[A-Za-z0-9_+./-]+")) && !item.startsWith('/') && ".." !in item.split('/')) { "头文件名无效" }
                    "imports" -> require(item.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) { "模块名无效" }
                    else -> require(item.matches(Regex("[A-Za-z0-9][A-Za-z0-9_+.-]*"))) { "$key 名称无效" }
                }
            }
        }
        if (requirements.has("compiler")) require(requirements.opt("compiler") is String && requirements.getString("compiler").matches(Regex("[A-Za-z0-9][A-Za-z0-9_+.-]*"))) { "compiler无效" }
        if (action == "verify") {
            val command = args.opt("command") as? JSONArray ?: throw IllegalArgumentException("verify需提供command参数数组")
            require(command.length() in 1..32 && (0 until command.length()).all { command.opt(it) is String && command.getString(it).isNotEmpty() && command.getString(it).length <= 4000 && '\u0000' !in command.getString(it) }) { "command参数无效" }
        } else require(!args.has("command") && !args.has("timeout_seconds")) { "仅verify接受command/timeout_seconds" }
        if (args.has("timeout_seconds")) {
            val value = args.opt("timeout_seconds")
            require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 1..40) { "验证时限需为1..40秒整数" }
        }
        return JSONObject(args.toString()).put("action", action)
    }
    fun specification(): JSONObject {
        fun strings(description: String) = JSONObject().put("type", "array").put("maxItems", 20).put("items", JSONObject().put("type", "string").put("maxLength", 200)).put("description", description)
        val requirements = JSONObject().put("commands", strings("所需可执行命令，如git、node、g++"))
            .put("python_packages", strings("Python包及版本条件，安装到当前会话venv"))
            .put("imports", strings("要实际导入的Python模块，如PIL.Image；分发包名与模块名可不同"))
            .put("system_packages", strings("明确需要的Ubuntu apt软件包名；全局共享，无需每会话重装"))
            .put("headers", strings("要试编译的头文件名，如gif_lib.h"))
            .put("libraries", strings("要试链接的库名，如gif；不含-l前缀"))
            .put("compiler", JSONObject().put("type", "string").put("description", "试编译使用的命令，默认cc，可选g++等"))
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", "environment")
            .put("description", "按任务声明通用环境需求。inspect核对命令/包版本/实际导入/头文件链接，并读取当前工作区项目依赖清单；prepare复用现有环境，安装明确缺少的Ubuntu系统包或当前会话Python包，from_project可按项目清单安装Python/npm依赖；verify执行明确的小样本command参数数组。无固定任务/图像格式枚举。已知缺包直接prepare，不机械先检查再重复检查；成功后直接开展任务。仅能验证声明的需求，不能保证任意程序。系统安装需环境空闲，先准备依赖再开持续终端；有使用者会返回busy，不自动停止。保留用户语言边界，缺少成熟库不意味着应手写编码器。verify会实际执行程序；不把可执行存在当作完整行为验证。")
            .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false).put("required", JSONArray(listOf("action")))
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("inspect", "prepare", "verify"))))
                    .put("requirements", JSONObject().put("type", "object").put("additionalProperties", false).put("properties", requirements))
                    .put("from_project", JSONObject().put("type", "boolean"))
                    .put("command", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 32).put("items", JSONObject().put("type", "string").put("maxLength", 4000)))
                    .put("timeout_seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 40)))))
    }
}

internal class CapabilityTool(private val context: Context, private val runtime: PythonRuntime) {
    companion object { private val installs = ConcurrentHashMap<String, Mutex>() }
    suspend fun execute(chat: String, args: JSONObject): String = try {
        val request = CapabilityContract.validate(args)
        if (request.getString("action") == "prepare") installs.getOrPut(context.filesDir.canonicalPath) { Mutex() }.withLock {
            val token = EnvironmentForeground.hold(context)
            try {
                runtime.ensureSession(chat)
                val packages = request.optJSONObject("requirements")?.optJSONArray("system_packages") ?: JSONArray()
                if (packages.length() > 0) {
                    val names = (0 until packages.length()).map { packages.getString(it) }
                    val test = runtime.runCommand(chat, listOf("/usr/bin/dpkg-query", "-W", "-f=\${db:Status-Status}\\n", *names.toTypedArray()))
                    val installed = test.startsWith("exit=0\n") && test.substringAfter('\n').lineSequence().filter { it.isNotBlank() }.all { it == "installed" }
                    if (!installed) {
                        val command = "apt-get update -qq && (dpkg --configure -a || DEBIAN_FRONTEND=noninteractive apt-get -f install -y --no-install-recommends) && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " + names.joinToString(" ") { "'$it'" }
                        val result = runtime.runCommand(chat, listOf("/bin/sh", "-c", command), 900, true)
                        if (!result.startsWith("exit=0\n")) return@withLock JSONObject().put("status", "installation_failed").put("evidence", result).toString()
                    }
                }
                invoke(chat, request, 1200)
            } finally { EnvironmentForeground.release(context, token) }
        } else invoke(chat, request, 60)
    } catch (cancel: CancellationException) { throw cancel }
    catch (error: IllegalArgumentException) { JSONObject().put("status", "invalid_argument").put("message", error.message).toString() }
    catch (error: Exception) { JSONObject().put("status", if (error.message?.let { "使用" in it || "正在准备依赖" in it } == true) "busy" else "error").put("message", error.message?.take(1500)).toString() }
    private suspend fun invoke(chat: String, request: JSONObject, timeout: Long): String {
        val payload = java.util.Base64.getEncoder().encodeToString(request.toString().toByteArray())
        val code = "import base64,json,runpy,contextlib,io\nprobe=runpy.run_path('/opt/capri/bootstrap/task_environment.py')\ncaptured=io.StringIO()\nwith contextlib.redirect_stdout(captured),contextlib.redirect_stderr(captured):\n result=probe['execute'](json.loads(base64.b64decode('$payload')))\nif captured.getvalue(): result['diagnostics']=captured.getvalue()[-4000:]\nprint(json.dumps(result,ensure_ascii=False))"
        val output = runtime.runCommand(chat, listOf("/root/.venv/bin/python3", "-c", code), timeout, resultLimit = 1_900_000)
        return if (output.startsWith("exit=0\n")) {
            val result = JSONObject(output.substringAfter('\n'))
            if (result.toString().length > 28_000) {
                result.remove("project"); result.remove("operations"); result.remove("diagnostics")
                result.put("truncated", "Large project/install diagnostics omitted; request specific requirements or inspect project files explicitly.")
            }
            result.toString()
        }
        else JSONObject().put("status", "error").put("evidence", output).toString()
    }
}
