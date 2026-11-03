package ai.capri.app

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.text.NumberFormat

@Composable internal fun SettingsDestination(vm: CapriModel, route: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName,0) }
    val debug = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    when(route) {
        "kernel" -> KernelSettings(onBack)
        "rules" -> ai.capri.app.agent.rules.RulesSettings(vm.userRules, vm.current().id, initiallyOpen = true, onClose = onBack)
        "root" -> ScrollDetail("Root", onBack) { ai.capri.app.nativebridge.RootPanel(vm) }
        "environment" -> ScrollDetail("执行环境", onBack) {
            ConfigIntro("全局环境 · 当前对话：${vm.current().title}", "查看准备状态、环境档案和维护操作。")
            ai.capri.app.environment.InstallationEnvironmentPanel(vm)
            EnvironmentPanel(vm)
        }
        "storage" -> ScrollDetail("存储管理", onBack) {
            ConfigIntro("当前对话 · ${vm.current().title}", "任务文件可在文件页预览和导出。")
            ConfigGroup("可用空间", "设备所在分区的剩余空间，不是 Capri 占用。") {
                Text("${NumberFormat.getNumberInstance().format(context.filesDir.usableSpace / 1024 / 1024)} MB",style=MaterialTheme.typography.headlineMedium)
            }
            EnvironmentPanel(vm, initiallyExpanded = true)
        }
        "connection" -> ScrollDetail("电脑网页连接", onBack) {
            ConfigIntro("USB · 电脑与手机", "网页与手机共用会话、模型和文件。")
            ConfigGroup("连接状态") { ConfigFact("当前版本",if(debug) "支持 USB 开发网页" else "未开放开发网页连接") }
            ConfigGroup("连接步骤") {
                ConfigFact("01 · 连接手机", "通过 USB 连接已授权的电脑，打开一次 Capri 启动内核后可退后台。")
                ConfigFact("02 · 打开网页", "在电脑启动项目的 Web 客户端，访问它显示的本地地址。")
                ConfigFact("03 · 核对连接", "以网页顶部的实时状态为准；手机更新或进程重启后刷新网页。")
            }
            ConfigHelp("公开贡献确认") { Text("公开 Skill 贡献仍需在手机原生确认，网页不能替你批准公开上传。",style=MaterialTheme.typography.bodySmall) }
        }
        "about" -> ScrollDetail("关于 Capri", onBack) {
            ConfigGroup("Capri") {
            Text("Capri ${packageInfo.versionName}", style = MaterialTheme.typography.headlineSmall)
            Text("版本 ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo)} · ${if(debug) "Debug" else "Release"}")
            Text("任务文件在手机处理；模型与联网能力按照已配置服务运行。")
            }
            ConfigHelp("高级运行说明") {
            Text("Android 原生调度 · PRoot/Ubuntu。环境共用 Android 内核，各会话属于同一应用 UID；目录分离不构成恶意代码安全隔离。")
            Text("工作区保存任务文件，临时目录与依赖可在存储管理中按范围维护。清理前会显示作用范围并要求确认。")
            }
        }
    }
}
