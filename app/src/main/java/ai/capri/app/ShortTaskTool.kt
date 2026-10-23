package ai.capri.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal object ShortTaskContract {
    fun validate(args: JSONObject): JSONObject {
        require(args.keys().asSequence().all { it in setOf("code", "steps", "requirements") }) { "未知短任务参数" }
        require(args.has("code") != args.has("steps")) { "code与steps二选一" }
        if (args.has("code")) require(args.opt("code") is String && args.getString("code").length in 1..128000) { "code需为1..128000字符" }
        if (args.has("requirements")) {
            require(args.opt("requirements") is JSONObject) { "requirements需为对象" }
            CapabilityContract.validate(JSONObject().put("action", "prepare").put("requirements", args.getJSONObject("requirements")))
        }
        if (args.has("steps")) {
            val steps = args.opt("steps") as? JSONArray ?: throw IllegalArgumentException("steps需为数组")
            require(steps.length() in 1..8) { "steps需含1..8步" }
            var length = 0
            for (i in 0 until steps.length()) {
                val step = steps.opt(i) as? JSONObject ?: throw IllegalArgumentException("步骤需为对象")
                require(step.keys().asSequence().all { it in setOf("name", "argv") }) { "未知步骤参数" }
                require(step.opt("name") is String && step.getString("name").matches(Regex("[A-Za-z0-9_-]{1,32}"))) { "步骤name需为简短英文标识" }
                val argv = step.opt("argv") as? JSONArray ?: throw IllegalArgumentException("步骤需提供argv数组")
                require(argv.length() in 1..64) { "argv需含1..64项" }
                for (j in 0 until argv.length()) {
                    val value = argv.opt(j)
                    require(value is String && value.length <= 8192 && '\u0000' !in value && (j != 0 || value.isNotBlank())) { "argv参数无效" }
                    length += value.length
                }
            }
            require(length <= 128000) { "步骤参数总长度超过128000" }
        }
        return JSONObject(args.toString())
    }
    fun specification(): JSONObject {
        fun string(max: Int) = JSONObject().put("type", "string").put("maxLength", max)
        val requirements = CapabilityContract.specification().getJSONObject("function").getJSONObject("parameters")
            .getJSONObject("properties").getJSONObject("requirements")
        val step = JSONObject().put("type", "object").put("additionalProperties", false).put("required", JSONArray(listOf("name", "argv")))
            .put("properties", JSONObject().put("name", string(32).put("pattern", "^[A-Za-z0-9_-]{1,32}$"))
                .put("argv", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 64).put("items", string(8192))))
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", "python_run")
            .put("description", "手机短任务：一次提交所需requirements和code或steps，客户端先准备依赖并实际验证，再直接执行，无需分开调用environment和执行。依赖失败时不执行代码。requirements与environment通用结构相同，只声明实际需要项；不猜测未声明模块对应的PyPI包。code执行Python编排脚本，可用subprocess调用用户指定语言，最长60秒；subprocess需check=True，外层exit=0不能证明被忽略的子进程成功。已有代码/命令优先用steps参数数组按顺序编译、运行、验证：客户端独立核对每步退出码，失败即停，保留中间文件，不自动重跑，整个执行部分共60秒。步骤间共享工作区和HOME，不共享Shell变量，明确Shell命令用/bin/sh -c。不使用持续终端完成短任务。返回结构化status/phase/exit_code/output/timings_ms；代码成功不等于产物已完整验证，应在脚本或步骤里放实际断言；成功后send_file发送所需产物。")
            .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", JSONObject().put("code", string(128000)).put("requirements", requirements)
                    .put("steps", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 8).put("items", step)))
                .put("oneOf", JSONArray().put(JSONObject().put("required", JSONArray(listOf("code"))))
                    .put(JSONObject().put("required", JSONArray(listOf("steps")))))))
    }
    fun encode(result: JSONObject): String {
        var text = result.toString()
        for (limit in listOf(12000, 6000, 2000, 500, 0)) {
            if (text.length <= 36000) break
            if (result.has("output")) result.put("output", result.getString("output").take(limit)).put("output_truncated", true)
            result.optJSONArray("steps")?.let { steps -> for (i in 0 until steps.length()) {
                steps.getJSONObject(i).let { step -> if (step.has("output")) step.put("output", step.getString("output").take(limit)).put("output_truncated", true) }
            } }
            result.optJSONObject("preparation")?.let { if (it.has("evidence")) it.put("evidence", it.getString("evidence").take(limit)) }
            result.put("result_truncated", true); text = result.toString()
        }
        return text
    }
    fun outcome(raw: String, limit: Int): JSONObject {
        val exit = raw.substringBefore('\n').removePrefix("exit=").toIntOrNull() ?: error("执行器未返回退出码")
        val output = raw.substringAfter('\n', "")
        return JSONObject().put("exit_code", exit).put("output", output.take(limit)).put("output_truncated", output.length > limit)
    }
}

internal class ShortTaskTool(private val runtime: PythonRuntime, private val environment: CapabilityTool) {
    suspend fun execute(chat: String, args: JSONObject, trace: PerformanceTrace? = null): String {
        val total = PerformanceTrace.now()
        val timings = JSONObject()
        var phase = "validate"
        var phaseStarted = total
        var index: Int? = null
        var prepared: JSONObject? = null
        val results = JSONArray()
        fun response(status: String, detail: String? = null): JSONObject {
            if (phase in setOf("prepare", "execute")) timings.put(phase, PerformanceTrace.elapsed(phaseStarted))
            return JSONObject().put("status", status).put("phase", phase)
            .put("timings_ms", timings.put("total", PerformanceTrace.elapsed(total))).apply {
                index?.let { put("failed_step", it) }
                detail?.let { put("message", it.take(1200)) }
                prepared?.let { put("preparation", it) }
                if (results.length() > 0) put("steps", results)
            }
        }
        try {
            val request = ShortTaskContract.validate(args)
            phase = "prepare"
            val preparationStart = PerformanceTrace.now(); phaseStarted = preparationStart
            var preparationStatus = PerformanceTrace.Status.ERROR
            try {
                runtime.ensureSession(chat)
                request.optJSONObject("requirements")?.takeIf { it.length() > 0 }?.let { required ->
                    val result = JSONObject(environment.execute(chat, JSONObject().put("action", "prepare").put("requirements", required)))
                    prepared = JSONObject().put("status", result.optString("status"))
                        .put("requirements_satisfied", result.optBoolean("requirements_satisfied"))
                        .put("missing", result.optJSONArray("missing") ?: JSONArray()).put("failed", result.optJSONArray("failed") ?: JSONArray())
                    result.optJSONObject("timings_ms")?.let { prepared!!.put("timings_ms", it) }
                    if (result.optString("status") != "ok" || !result.optBoolean("requirements_satisfied")) {
                        prepared!!.put("evidence", result.toString().take(7000))
                        return ShortTaskContract.encode(response(if (result.optString("status") == "busy") "busy" else "preparation_failed"))
                    }
                }
                preparationStatus = PerformanceTrace.Status.OK
            } catch (cancel: CancellationException) { preparationStatus = PerformanceTrace.Status.CANCELLED; throw cancel }
            finally {
                val elapsed = PerformanceTrace.elapsed(preparationStart); timings.put("prepare", elapsed)
                trace?.event(PerformanceTrace.Stage.PREPARE, elapsed, preparationStatus, tool = "python_run", details = prepared?.optJSONObject("timings_ms"))
            }
            phase = "execute"
            val executionStart = PerformanceTrace.now(); phaseStarted = executionStart
            var executionStatus = PerformanceTrace.Status.ERROR
            try {
                val executed = withTimeoutOrNull(60000) {
                    if (request.has("code")) ShortTaskContract.outcome(runtime.execute(chat, request.getString("code")), 24000)
                    else {
                        val steps = request.getJSONArray("steps")
                        for (i in 0 until steps.length()) {
                            index = i
                            val step = steps.getJSONObject(i); val argv = step.getJSONArray("argv")
                            val start = PerformanceTrace.now()
                            var status = PerformanceTrace.Status.ERROR
                            try {
                                val result = ShortTaskContract.outcome(runtime.runCommand(chat, (0 until argv.length()).map { argv.getString(it) }), 3000)
                                    .put("name", step.getString("name")).put("index", i).put("duration_ms", PerformanceTrace.elapsed(start))
                                results.put(result)
                                if (result.getInt("exit_code") != 0) return@withTimeoutOrNull result
                                status = PerformanceTrace.Status.OK
                            } catch (cancel: CancellationException) { status = PerformanceTrace.Status.CANCELLED; throw cancel }
                            finally { trace?.event(PerformanceTrace.Stage.STEP, PerformanceTrace.elapsed(start), status, tool = "python_run", step = i) }
                        }
                        index = null
                        JSONObject().put("exit_code", 0)
                    }
                }
                if (executed == null) { executionStatus = PerformanceTrace.Status.TIMEOUT; return ShortTaskContract.encode(response("timeout", "执行部分累计超过60秒；已停止，已有中间文件保留，不自动重跑")) }
                val success = executed.getInt("exit_code") == 0
                executionStatus = if (success) PerformanceTrace.Status.OK else PerformanceTrace.Status.ERROR
                if (success) index = null
                return ShortTaskContract.encode(response(if (success) "ok" else "execution_failed").put("exit_code", executed.getInt("exit_code")).apply {
                    if (executed.has("output")) put("output", executed.getString("output")).put("output_truncated", executed.optBoolean("output_truncated"))
                })
            } catch (cancel: CancellationException) { executionStatus = PerformanceTrace.Status.CANCELLED; throw cancel }
            finally {
                val elapsed = PerformanceTrace.elapsed(executionStart); timings.put("execute", elapsed)
                trace?.event(PerformanceTrace.Stage.EXECUTE, elapsed, executionStatus, tool = "python_run")
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: IllegalArgumentException) { return ShortTaskContract.encode(response("invalid_argument", error.message)) }
        catch (error: Exception) { return ShortTaskContract.encode(response("error", error.message)) }
    }
}
