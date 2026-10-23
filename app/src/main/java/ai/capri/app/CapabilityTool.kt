package ai.capri.app

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal object CapabilityContract {
    fun validate(args: JSONObject): JSONObject {
        require(args.keys().asSequence().all { it in setOf("language", "outputs", "allow_helpers") }) { "未知环境检查参数" }
        require(args.opt("language") in listOf("python", "cpp", "node")) { "language需为python/cpp/node" }
        require(args.opt("allow_helpers") is Boolean) { "allow_helpers必须明确为布尔值；纯指定语言任务传false" }
        val outputs = args.opt("outputs") as? JSONArray ?: throw IllegalArgumentException("outputs需为数组")
        require(outputs.length() <= 3) { "最多检查3项输出能力" }
        val values = (0 until outputs.length()).map { outputs.opt(it) }
        require(values.all { it is String && it in listOf("png", "gif", "chart") } && values.distinct().size == values.size) { "outputs仅接受不重复的png/gif/chart；仅代码执行传空数组" }
        return args
    }
    fun specification(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "environment_check")
        .put("description", "按当前任务需要检查当前会话环境：实际验证Python/C++17/Node执行，PNG/GIF编码解码或Matplotlib图表；区分已可用、会话未安装但可离线安装、库损坏、缺原生后端和未验证，并返回建议路径。无需提交用户全文，不安装包、不联网。环境不明、语言/输出要求变化或失败时按需调用，不要每轮机械全检。用户要求纯C++/纯Node时allow_helpers=false；允许辅助编码时true，并向用户说明语言分工。C++不等于零依赖。仅覆盖此能力目录，不是任意任务成功保证；其他系统/网络权限用android或相应工具核验。")
        .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("language", "outputs", "allow_helpers")))
            .put("properties", JSONObject()
                .put("language", JSONObject().put("type", "string").put("enum", JSONArray(listOf("python", "cpp", "node"))))
                .put("outputs", JSONObject().put("type", "array").put("maxItems", 3).put("uniqueItems", true)
                    .put("items", JSONObject().put("type", "string").put("enum", JSONArray(listOf("png", "gif", "chart")))))
                .put("allow_helpers", JSONObject().put("type", "boolean")))))
}

internal class CapabilityTool(private val runtime: PythonRuntime) {
    suspend fun execute(chat: String, args: JSONObject): String = try {
        val request = CapabilityContract.validate(args)
        val code = "import json,runpy\nprobe=runpy.run_path('/opt/capri/bootstrap/capability_probe.py')\nprint(json.dumps(probe['assess'](json.loads(${JSONObject.quote(request.toString())})),ensure_ascii=False))"
        val output = runtime.execute(chat, code)
        if (output.startsWith("exit=0\n")) JSONObject(output.substringAfter('\n')).toString()
        else JSONObject().put("status", "error").put("readiness", "unknown").put("evidence", output.take(4000)).toString()
    } catch (cancel: CancellationException) { throw cancel }
    catch (error: IllegalArgumentException) { JSONObject().put("status", "invalid_argument").put("message", error.message).toString() }
    catch (error: Exception) { JSONObject().put("status", "error").put("readiness", "unknown").put("message", error.message?.take(1000)).toString() }
}
