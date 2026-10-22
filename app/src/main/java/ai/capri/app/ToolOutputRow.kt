package ai.capri.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val ToolMuted = Color(0xFF939E94)

/** Output stays uncomposed until expanded; folding does not modify the recorded result. */
@Composable internal fun ToolOutputRow(output: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(output) { mutableStateOf(false) }
    val name = remember(output) { output.substringBefore('\n') }
    val body = remember(output) { output.substringAfter('\n', "") }
    val json = remember(body) { runCatching { JSONObject(body) }.getOrNull() }
    val label = remember(name, json) {
        when (name) {
            "android" -> json?.optString("action")?.let { ai.capri.app.nativebridge.NativeCatalog.find(it)?.title } ?: "Android 原生工具"
            "terminal" -> when (json?.optString("action")) {
                "open" -> "创建终端"; "list", "attach" -> "查看终端"; "run" -> "执行命令"
                "read" -> "读取终端输出"; "input" -> "输入终端"; "cancel" -> "停止命令"
                "close" -> "关闭终端"; "clear_history", "delete" -> "清理终端记录"; else -> "终端"
            }
            "python_env" -> when (json?.optString("action")) {
                "install" -> "安装 Python 依赖"; "uninstall" -> "卸载 Python 依赖"
                "list", "freeze" -> "查看 Python 依赖"; "check" -> "检查 Python 依赖"; else -> "诊断 Python 环境"
            }
            "python_run" -> "运行 Python"
            "list_files" -> "查看工作区"
            "read_file" -> "读取文件"
            "write_file" -> "写入文件"
            else -> name
        }
    }
    val status = json?.optString("status").orEmpty()
    val commandState = json?.optJSONObject("data")?.optJSONObject("command")?.optString("state").orEmpty()
    val failed = commandState in setOf("failed", "timeout", "output_limit", "interrupted") || status in setOf("error", "restricted", "permission_denied", "invalid_argument", "unsupported", "timeout", "unavailable", "not_found", "not_running") || body.startsWith("工具失败") || (name == "python_run" && body.startsWith("exit=") && !body.startsWith("exit=0\n"))
    val summary = when (status) {
        "invalid_argument" -> "参数不合法"
        "permission_denied", "restricted" -> "权限受限"
        "not_found" -> "记录不存在"
        "unavailable" -> "当前不可用"
        "timeout" -> "调用超时"
        "partial" -> "返回部分信息"
        "permission_required" -> "需要授权"
        "foreground_required" -> "需要返回应用"
        "setting_required", "user_action_required" -> "需要继续操作"
        "busy" -> "等待处理"
        else -> when (commandState) {
            "failed" -> "命令退出 ${json?.optJSONObject("data")?.optJSONObject("command")?.optInt("exit_code", -1)}"
            "timeout" -> "命令超时"
            "output_limit" -> "输出达到上限"
            "interrupted" -> "命令已中断"
            "cancelled" -> "命令已取消"
            "running" -> "运行中"
            else -> if (failed) "工具执行失败" else ""
        }
    }
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).clickable(role = Role.Button, onClickLabel = if (expanded) "收起输出" else "展开输出") { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (summary.isEmpty()) label else "$label · $summary", modifier = Modifier.weight(1f, fill = false),
                fontSize = 13.sp, lineHeight = 20.sp, color = if (failed) Color(0xFFFF514F) else ToolMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(4.dp))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = ToolMuted, modifier = Modifier.size(16.dp))
        }
        if (expanded) {
            val displayed = remember(body) { json?.toString(2) ?: body }
            SelectionContainer {
                Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
                    Text(displayed, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, color = ToolMuted)
                }
            }
            TextButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Capri工具输出", body)) }, modifier = Modifier.padding(horizontal = 6.dp)) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("复制输出", fontSize = 11.sp)
            }
        }
    }
}
