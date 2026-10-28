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
        val action = args.optString("action", "inspect")
        if (action in listOf("lookup", "document", "record")) return ai.capri.app.environment.EnvironmentActions.validate(args)
        require(args.keys().asSequence().all { it in fields }) { "未知环境参数" }
        require(action in listOf("inspect", "refresh", "prepare", "verify")) { "未知环境action" }
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
        val specification = JSONObject().put("type", "function").put("function", JSONObject().put("name", "environment")
            .put("description", "安装环境档案与通用检测入口。lookup查询全安装任意软硬件/驱动事实，document分页读取Markdown，record凭真实工具environment_receipt写入观察；refresh按requirements强制刷新。inspect先复用有效证据，只补测缺失项；prepare实际准备依赖，verify始终实际执行。inspect核对命令/包版本/实际导入/头文件链接，并读取当前工作区项目依赖清单；prepare复用现有环境，安装明确缺少的Ubuntu系统包或当前会话Python包，from_project可按项目清单安装Python/npm依赖；verify执行明确的小样本command参数数组。无固定任务/图像格式枚举。已知缺包直接prepare，不机械先检查再重复检查；成功后直接开展任务。仅能验证声明的需求，不能保证任意程序。系统安装需环境空闲，先准备依赖再开持续终端；有使用者会返回busy，不自动停止。保留用户语言边界，缺少成熟库不意味着应手写编码器。verify会实际执行程序；不把可执行存在当作完整行为验证。")
            .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false).put("required", JSONArray(listOf("action")))
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("inspect", "refresh", "prepare", "verify", "lookup", "document", "record"))))
                    .put("requirements", JSONObject().put("type", "object").put("additionalProperties", false).put("properties", requirements))
                    .put("from_project", JSONObject().put("type", "boolean"))
                    .put("command", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 32).put("items", JSONObject().put("type", "string").put("maxLength", 4000)))
                    .put("timeout_seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 40)))))
        ai.capri.app.environment.EnvironmentActions.extend(specification)
        return specification
    }
}

internal class CapabilityTool(private val context: Context, private val runtime: PythonRuntime, profileOverride: ai.capri.app.environment.InstallationEnvironment? = null) {
    companion object { private val installs = ConcurrentHashMap<String, Mutex>() }
    private val profile by lazy { profileOverride ?: ai.capri.app.environment.InstallationEnvironment.get(context) }
    suspend fun execute(chat: String, args: JSONObject, trace: PerformanceTrace? = null): String {
        val started = PerformanceTrace.now()
        val phases = JSONObject()
        val result = try {
        val request = CapabilityContract.validate(args)
        if (request.getString("action") in listOf("lookup", "document", "record")) return when (request.getString("action")) {
            "lookup" -> profile.lookup(request)
            "document" -> profile.document(request)
            else -> profile.record(request)
        }
        if (request.getString("action") == "prepare") installs.getOrPut(context.filesDir.canonicalPath) { Mutex() }.withLock {
            phases.put("queue", PerformanceTrace.elapsed(started))
            val token = EnvironmentForeground.hold(context)
            val scope = if ((request.optJSONObject("requirements")?.optJSONArray("system_packages")?.length() ?: 0) > 0) "ubuntu_shared" else "session:$chat"
            runCatching { profile.invalidate(scope, "依赖准备可能改变环境，需按实际结果更新") }
            try {
                timed(phases, "session") { runtime.ensureSession(chat) }
                val packages = request.optJSONObject("requirements")?.optJSONArray("system_packages") ?: JSONArray()
                if (packages.length() > 0) {
                    val names = (0 until packages.length()).map { packages.getString(it) }
                    val test = timed(phases, "system_check") { runtime.runCommand(chat, listOf("/usr/bin/dpkg-query", "-W", "-f=\${db:Status-Status}\\n", *names.toTypedArray())) }
                    val installed = test.startsWith("exit=0\n") && test.substringAfter('\n').lineSequence().filter { it.isNotBlank() }.toList().let { it.size == names.size && it.all { line -> line == "installed" } }
                    if (!installed) {
                        val command = "apt-get update -qq && (dpkg --configure -a || DEBIAN_FRONTEND=noninteractive apt-get -f install -y --no-install-recommends) && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " + names.joinToString(" ") { "'$it'" }
                        val result = timed(phases, "system_install") { runtime.runCommand(chat, listOf("/bin/sh", "-c", command), 900, true) }
                        if (!result.startsWith("exit=0\n")) return@withLock JSONObject().put("status", "installation_failed").put("evidence", result).toString()
                    }
                }
                invoke(chat, request, 1200)
            } finally { EnvironmentForeground.release(context, token) }
        } else invoke(chat, request, 60)
    } catch (cancel: CancellationException) { throw cancel }
    catch (error: IllegalArgumentException) { JSONObject().put("status", "invalid_argument").put("message", error.message).toString() }
    catch (error: Exception) { JSONObject().put("status", if (error.message?.let { "使用" in it || "正在准备依赖" in it } == true) "busy" else "error").put("message", error.message?.take(1500)).toString() }
        val objectResult = JSONObject(result)
        val timing = objectResult.optJSONObject("timings_ms") ?: JSONObject()
        phases.keys().forEach { timing.put(it, phases.get(it)) }
        timing.put("client_total", PerformanceTrace.elapsed(started))
        trace?.event(PerformanceTrace.Stage.ENVIRONMENT, PerformanceTrace.elapsed(started),
            if (objectResult.optString("status") == "ok") PerformanceTrace.Status.RETURNED else PerformanceTrace.Status.ERROR,
            tool = "environment", details = timing)
        objectResult.put("timings_ms", timing)
        if (!objectResult.optBoolean("cached") && args.optString("action", "inspect") in setOf("inspect", "refresh", "prepare", "verify")) runCatching {
            objectResult.put("environment_receipt", profile.receipt("environment.${args.optString("action", "inspect")}", "session:$chat", objectResult.toString()))
        }
        return objectResult.toString()
    }
    private suspend fun <T> timed(phases: JSONObject, key: String, block: suspend () -> T): T {
        val started = PerformanceTrace.now()
        try { return block() } finally { phases.put(key, PerformanceTrace.elapsed(started)) }
    }
    private suspend fun invoke(chat: String, request: JSONObject, timeout: Long): String {
        val effective = JSONObject(request.toString())
        val requirements = request.optJSONObject("requirements") ?: JSONObject()
        if (request.getString("action") == "refresh") {
            runCatching { profile.invalidateChecks(chat, requirements, "用户要求强制刷新指定项目") }
            effective.put("action", "inspect")
        }
        val known = if (request.getString("action") == "inspect") runCatching { profile.cachedChecks(chat, requirements) }.getOrDefault(JSONObject()) else JSONObject()
        val keys = profile.checkKeys(requirements)
        if (keys.isNotEmpty() && keys.all { known.has(it) }) {
            val cacheResult = JSONObject().put("status", "ok").put("scope", "current chat").put("checks", known)
                .put("requirements_satisfied", keys.all { key -> val item = known.getJSONObject(key); item.opt("present") != false && item.opt("satisfies") != false && item.opt("passed") != false })
                .put("missing", JSONArray(keys.filter { known.getJSONObject(it).opt("present") == false || known.getJSONObject(it).opt("satisfies") == false }))
                .put("failed", JSONArray(keys.filter { known.getJSONObject(it).opt("passed") == false })).put("cached", true)
                .put("project", JSONObject().put("notice", "此次复用环境档案；项目清单由当前指纹约束，必要时直接读项目文件"))
                .put("notice", "未启动检测进程；各项verified_at是原验证时间，不是本次时间。明确verify仍会执行。")
            return cacheResult.toString()
        }
        // Private client field. It is never accepted from the model/user tool arguments.
        if (known.length() > 0) {
            val cached = JSONObject(known.toString())
            keys.filter { it.startsWith("native:") }.forEachIndexed { i, key ->
                if (cached.has(key)) { cached.put(if (i == 0) "native_compile_link" else "native_load", cached.get(key)); cached.remove(key) }
            }
            effective.put("_cached_checks", cached)
        }
        val payload = java.util.Base64.getEncoder().encodeToString(effective.toString().toByteArray())
        val code = "import base64,json,runpy,contextlib,io\nprobe=runpy.run_path('/opt/capri/bootstrap/task_environment.py')\ncaptured=io.StringIO()\nwith contextlib.redirect_stdout(captured),contextlib.redirect_stderr(captured):\n result=probe['execute'](json.loads(base64.b64decode('$payload')))\nif captured.getvalue(): result['diagnostics']=captured.getvalue()[-4000:]\nprint(json.dumps(result,ensure_ascii=False))"
        val output = runtime.runCommand(chat, listOf("/root/.venv/bin/python3", "-c", code), timeout, resultLimit = 1_900_000)
        return if (output.startsWith("exit=0\n")) {
            val result = JSONObject(output.substringAfter('\n'))
            runCatching { profile.observeGuest(chat, request, result) }.onFailure { result.put("environment_archive_warning", "环境档案更新未完成，原检测结果仍有效；稍后检查档案，不因此重做已完成操作。") }
            if (result.toString().length > 28_000) {
                result.remove("project"); result.remove("operations"); result.remove("diagnostics")
                result.put("truncated", "Large project/install diagnostics omitted; request specific requirements or inspect project files explicitly.")
            }
            result.toString()
        }
        else JSONObject().put("status", "error").put("evidence", output).toString()
    }
}
