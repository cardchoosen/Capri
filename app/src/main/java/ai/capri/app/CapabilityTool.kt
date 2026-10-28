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
        require(!args.has("action") || args.opt("action") is String) { "action must be a string." }
        val action = args.optString("action", "inspect")
        if (action in listOf("lookup", "document", "record")) return ai.capri.app.environment.EnvironmentActions.validate(args)
        require(args.keys().asSequence().all { it in fields }) { "Unknown environment argument." }
        require(action in listOf("inspect", "refresh", "prepare", "verify")) { "Unknown environment action." }
        if (args.has("from_project")) require(action == "prepare" && args.opt("from_project") is Boolean) { "from_project must be a boolean and is only accepted by prepare." }
        val requirements = if (args.has("requirements")) args.opt("requirements") as? JSONObject ?: throw IllegalArgumentException("requirements must be an object.") else JSONObject()
        require(requirements.keys().asSequence().all { it in requirementFields }) { "Unknown requirement type." }
        for (key in requirementFields - "compiler") {
            if (!requirements.has(key)) continue
            val list = requirements.opt(key) as? JSONArray ?: throw IllegalArgumentException("$key must be an array.")
            require(list.length() in 0..20) { "$key accepts at most 20 items." }
            for (i in 0 until list.length()) {
                val item = list.opt(i)
                require(item is String && item.length in 1..200) { "$key entries must contain 1..200 characters." }
                when (key) {
                    "python_packages" -> ai.capri.app.pythonenv.PythonEnvironmentContract.validate(JSONObject().put("action", "install").put("packages", JSONArray(listOf(item))))
                    "headers" -> require(item.matches(Regex("[A-Za-z0-9_+./-]+")) && !item.startsWith('/') && ".." !in item.split('/')) { "Invalid relative header name." }
                    "imports" -> require(item.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) { "Invalid Python import name." }
                    else -> require(item.matches(Regex("[A-Za-z0-9][A-Za-z0-9_+.-]*"))) { "Invalid $key name." }
                }
            }
        }
        if (requirements.has("compiler")) require(requirements.opt("compiler") is String && requirements.getString("compiler").length <= 200 && requirements.getString("compiler").matches(Regex("[A-Za-z0-9][A-Za-z0-9_+.-]*"))) { "Invalid compiler command name." }
        val allowed = when (action) {
            "prepare" -> setOf("action", "requirements", "from_project")
            "verify" -> setOf("action", "requirements", "command", "timeout_seconds")
            else -> setOf("action", "requirements")
        }
        require(args.keys().asSequence().all { it in allowed }) { "Argument is not supported by this environment action." }
        if (action == "verify") {
            val command = args.opt("command") as? JSONArray ?: throw IllegalArgumentException("verify requires a command argument array.")
            require(command.length() in 1..32 && (0 until command.length()).all { command.opt(it) is String && command.getString(it).isNotEmpty() && command.getString(it).length <= 4000 && '\u0000' !in command.getString(it) }) { "Invalid command argument array." }
        } else require(!args.has("command") && !args.has("timeout_seconds")) { "Only verify accepts command and timeout_seconds." }
        if (args.has("timeout_seconds")) {
            val value = args.opt("timeout_seconds")
            require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 1..40) { "timeout_seconds must be an integer in 1..40." }
        }
        return JSONObject(args.toString()).put("action", action)
    }
    fun requirementsSchema(): JSONObject {
        fun strings(description: String) = JSONObject().put("type", "array").put("maxItems", 20).put("items", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 200)).put("description", description)
        val properties = JSONObject().put("commands", strings("Required executable names, such as git or g++."))
            .put("python_packages", strings("Python distributions with optional extras/version constraints; installed in the chat venv."))
            .put("imports", strings("Python modules to actually import; module names may differ from distribution names."))
            .put("system_packages", strings("Explicit Ubuntu apt package names; shared across chats."))
            .put("headers", strings("Relative headers to compile, such as gif_lib.h."))
            .put("libraries", strings("Libraries to link, such as gif; omit the -l prefix."))
            .put("compiler", JSONObject().put("type", "string").put("maxLength", 200).put("description", "Compiler executable, default cc."))
        return JSONObject().put("type", "object").put("additionalProperties", false).put("properties", properties)
    }
    fun specification(): JSONObject {
        val properties = ai.capri.app.environment.EnvironmentActions.properties()
            .put("requirements", requirementsSchema())
            .put("from_project", JSONObject().put("type", "boolean").put("description", "prepare only: install Python/npm dependencies from project manifests."))
            .put("command", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 32).put("items", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 4000)).put("description", "verify only: executable and argument array; actually runs the program."))
            .put("timeout_seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 40).put("description", "verify execution deadline; default 30 seconds."))
        val fields = linkedMapOf("inspect" to setOf("requirements"), "refresh" to setOf("requirements"), "prepare" to setOf("requirements", "from_project"), "verify" to setOf("requirements", "command", "timeout_seconds"))
        fields.putAll(ai.capri.app.environment.EnvironmentActions.fields)
        val schema = ActionSchema.build(properties, fields, { action -> if (action == "verify") listOf("command") else ai.capri.app.environment.EnvironmentActions.required(action) })
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", "environment")
            .put("description", "Task-driven dependency preparation and installation-wide evidence. inspect reuses valid checks and probes missing evidence; refresh forces declared checks. prepare installs explicit missing system/chat dependencies; verify always runs a small command. Known short-task requirements belong in python_run instead of a separate preparation call. lookup queries facts, document pages the Markdown archive, record stores receipt-backed observations. Only declared requirements are verified. Shared system changes require an idle environment; busy never stops other tasks. Historical failure is not permanent inability; cached downloads are not installed packages.")
            .put("parameters", schema))
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
        if (request.getString("action") in listOf("lookup", "document", "record")) return ExecutionResult.environment(JSONObject(when (request.getString("action")) {
            "lookup" -> profile.lookup(request)
            "document" -> profile.document(request)
            else -> profile.record(request)
        }))
        if (request.getString("action") == "prepare") installs.getOrPut(context.filesDir.canonicalPath) { Mutex() }.withLock {
            phases.put("queue", PerformanceTrace.elapsed(started))
            val token = EnvironmentForeground.hold(context)
            val scope = if ((request.optJSONObject("requirements")?.optJSONArray("system_packages")?.length() ?: 0) > 0) "ubuntu_shared" else "session:$chat"
            runCatching { profile.invalidate(scope, "Dependency preparation may change the environment; update from actual results.") }
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
    catch (error: IllegalArgumentException) { JSONObject().put("status", "invalid_argument").put("message", ExecutionResult.message(error)).toString() }
    catch (error: Exception) { JSONObject().put("status", if (error is RuntimeBusyException) "busy" else "error").put("message", ExecutionResult.message(error)).toString() }
        val objectResult = JSONObject(result)
        if (objectResult.optJSONObject("verification")?.optBoolean("passed") == false) objectResult.put("status", "verification_failed")
        ExecutionResult.annotate(objectResult)
        val timing = objectResult.optJSONObject("timings_ms") ?: JSONObject()
        phases.keys().forEach { timing.put(it, phases.get(it)) }
        timing.put("client_total", PerformanceTrace.elapsed(started))
        trace?.event(PerformanceTrace.Stage.ENVIRONMENT, PerformanceTrace.elapsed(started),
            if (objectResult.optString("status") == "ok") PerformanceTrace.Status.RETURNED else PerformanceTrace.Status.ERROR,
            tool = "environment", details = timing)
        objectResult.put("timings_ms", timing)
        val bounded = JSONObject(ExecutionResult.environment(objectResult))
        if (!objectResult.optBoolean("cached") && args.optString("action", "inspect") in setOf("inspect", "refresh", "prepare", "verify")) runCatching {
            bounded.put("environment_receipt", profile.receipt("environment.${args.optString("action", "inspect")}", "session:$chat", bounded.toString()))
        }
        return bounded.toString()
    }
    private suspend fun <T> timed(phases: JSONObject, key: String, block: suspend () -> T): T {
        val started = PerformanceTrace.now()
        try { return block() } finally { phases.put(key, PerformanceTrace.elapsed(started)) }
    }
    private suspend fun invoke(chat: String, request: JSONObject, timeout: Long): String {
        val effective = JSONObject(request.toString())
        val requirements = request.optJSONObject("requirements") ?: JSONObject()
        if (request.getString("action") == "refresh") {
            runCatching { profile.invalidateChecks(chat, requirements, "Explicit refresh requested for these checks.") }
            effective.put("action", "inspect")
        }
        val known = if (request.getString("action") == "inspect") runCatching { profile.cachedChecks(chat, requirements) }.getOrDefault(JSONObject()) else JSONObject()
        val keys = profile.checkKeys(requirements)
        if (keys.isNotEmpty() && keys.all { known.has(it) }) {
            val cacheResult = JSONObject().put("status", "ok").put("scope", "current chat").put("checks", known)
                .put("requirements_satisfied", keys.all { key -> val item = known.getJSONObject(key); item.opt("present") != false && item.opt("satisfies") != false && item.opt("passed") != false })
                .put("missing", JSONArray(keys.filter { known.getJSONObject(it).opt("present") == false || known.getJSONObject(it).opt("satisfies") == false }))
                .put("failed", JSONArray(keys.filter { known.getJSONObject(it).opt("passed") == false })).put("cached", true)
                .put("project", JSONObject().put("notice", "Cached archive checks reused under the current fingerprint; read project manifests directly if needed."))
                .put("notice", "No probe was started. verified_at refers to the original check; explicit verify always executes.")
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
            runCatching { profile.observeGuest(chat, request, result) }.onFailure { result.put("environment_archive_warning", "Archive update failed; original probe results remain available. Inspect the archive later without replaying completed operations.") }
            if (result.toString().length > 28_000) {
                result.remove("project"); result.remove("operations"); result.remove("diagnostics")
                result.put("truncated", "Large project/install diagnostics omitted; request specific requirements or inspect project files explicitly.")
            }
            result.toString()
        }
        else JSONObject().put("status", "error").put("evidence", output).toString()
    }
}
