package ai.capri.app

import org.json.JSONObject

internal data class ToolActivity(val id: String, val label: String)

internal fun toolLabel(name: String, args: JSONObject?): String = when (name) {
    "android" -> args?.optString("action")?.let { ai.capri.app.nativebridge.NativeCatalog.find(it)?.title } ?: "Android 原生工具"
    "terminal" -> when (args?.optString("action")) {
        "open" -> "创建终端"; "list", "attach" -> "查看终端"; "run" -> "执行命令"; "read" -> "读取终端输出"
        "input" -> "输入终端"; "cancel" -> "停止命令"; "close" -> "关闭终端"
        "clear_history", "delete" -> "清理终端记录"; else -> "终端"
    }
    "browser" -> when (args?.optString("action")) { "open" -> "打开网页"; "observe" -> "读取网页"; "show" -> "切换网页"; "close" -> "关闭网页"; else -> "查看浏览器" }
    "python_env" -> when (args?.optString("action")) {
        "install" -> "安装 Python 依赖"; "uninstall" -> "卸载 Python 依赖"; "list", "freeze" -> "查看 Python 依赖"
        "check" -> "检查 Python 依赖"; else -> "诊断 Python 环境"
    }
    "environment", "environment_check" -> when (args?.optString("action")) { "prepare" -> "准备任务环境"; "verify" -> "验证任务环境"; else -> "检查任务环境" }
    "extensions" -> when (args?.optString("action")) { "install_skill" -> "安装技能"; "install_mcp" -> "安装 MCP"; "install_toolpack" -> "安装工具包"; "upgrade_toolpack" -> "升级工具包"; "enable_toolpack" -> "启停工具包"; "uninstall_toolpack" -> "卸载工具包"; "load_skill" -> "加载技能"; "activate" -> "连接扩展"; "deactivate" -> "断开扩展"; "read_resource" -> "读取技能资源"; else -> "查找扩展" }
    "python_run" -> "运行 Python"; "list_files" -> "查看工作区"; "read_file" -> "读取文件"
    "write_file" -> "写入文件"; "send_file" -> "发送附件"; else -> name
}
internal fun toolRecordLabel(entry: Entry): String = toolLabel(entry.text.substringBefore('\n'),
    runCatching { JSONObject(entry.text.substringAfter('\n')) }.getOrNull())
