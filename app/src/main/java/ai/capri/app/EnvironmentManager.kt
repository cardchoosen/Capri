package ai.capri.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

internal data class EnvironmentComponent(val id: String, val title: String, val version: String,
    val state: String, val bytes: Long, val detail: String, val valid: Boolean)
internal data class EnvironmentReport(val components: List<EnvironmentComponent>, val active: List<RuntimeGate.Use>,
    val workspace: Long, val dependencies: Long, val cache: Long, val legacy: Long, val checked: Boolean,
    val probe: String = "") {
    val runtimeSummary: String get() {
        if (probe.isBlank()) return ""
        if (!probe.startsWith("exit=0\n")) return "运行检查失败：" + probe.take(400)
        return runCatching {
            val tools = JSONObject(probe.substringAfter('\n')).getJSONObject("tools")
            val failed = tools.keys().asSequence().filter { !tools.getJSONObject(it).optBoolean("available") }.toList()
            if (failed.isEmpty()) "运行检查通过" else "运行异常：" + failed.joinToString()
        }.getOrElse { "诊断结果格式异常，请查看详细输出。" }
    }
}

internal class EnvironmentManager(private val context: Context, private val runtime: PythonRuntime) {
    suspend fun inspect(chat: String, deep: Boolean): EnvironmentReport = withContext(Dispatchers.IO) {
        val report = RuntimeGate.observe {
            val components = ManagedBundle.all.map { spec ->
                val target = spec.target(context)
                val expected = spec.expected(context)
                val marker = File(target, ".complete").let { if (it.isFile) it.readText().trim() else "" }
                val installed = target.exists()
                val integrity = if (deep && installed) ManagedBundle.integrity(context, spec) else null
                val compatible = spec == ManagedBundle.base || marker == expected
                val state = when {
                    !installed -> if (ManagedBundle.legacy(spec, context).isNotEmpty()) "待迁移" else "未初始化"
                    integrity != null && integrity.problems > 0 -> "检测到差异"
                    !compatible -> "需更新"
                    integrity != null -> "校验通过"
                    else -> "已部署 · 未校验"
                }
                val detail = when {
                    integrity != null && integrity.problems > 0 -> "${integrity.problems}项缺失、改动或权限异常：\n" + integrity.details.joinToString("\n")
                    state == "待迁移" -> "下次使用前迁移到单一当前目录，成功后清理旧工具目录。"
                    state == "需更新" -> "组件与当前应用不匹配；关闭终端后更新此组件。"
                    deep && installed -> "已核对随应用提供的文件、链接与执行权限；不校验运行时生成的Python字节码。"
                    !installed -> "可从应用内置资源初始化，无需联网。"
                    else -> "点击检查环境以校验文件并实际运行诊断。"
                }
                EnvironmentComponent(spec.id, spec.title, version(spec), state, ManagedBundle.size(target), detail,
                    installed && compatible && (integrity == null || integrity.problems == 0))
            }
            val home = File(context.filesDir, "sessions/$chat/home")
            EnvironmentReport(components, RuntimeGate.active(context), ManagedBundle.size(File(context.filesDir, "sessions/$chat/workspace")),
                ManagedBundle.size(File(home, ".venv")) + ManagedBundle.size(File(home, ".local/npm")),
                ManagedBundle.size(File(home, ".cache")) + ManagedBundle.size(File(context.filesDir, "sessions/$chat/tmp")),
                ManagedBundle.all.sumOf { spec -> ManagedBundle.legacy(spec, context).sumOf { ManagedBundle.size(it) } }, deep)
        }
        if (!deep || report.components.any { !it.valid }) report
        else {
            val probe = try { runtime.execute(chat, "exec(open('/opt/capri/bootstrap/environment_probe.py').read())") }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) { "运行诊断未完成：${error.message?.take(500)}" }
            report.copy(probe = probe)
        }
    }
    suspend fun repair(id: String) = withContext(Dispatchers.IO) {
        ManagedBundle.repair(context, ManagedBundle.spec(id)); Unit
    }
    private fun version(spec: BundleSpec): String = when (spec.id) {
        "base" -> "Alpine 3.22.2 · Python 3.12.15"
        "tools" -> {
            "Node 22.23.2 · npm 11.6.4 · GCC 14.2 · Git / SSH"
        }
        else -> "Matplotlib 3.9.3 · NumPy 2.2.4 · Pillow 11.2.1"
    }
}
