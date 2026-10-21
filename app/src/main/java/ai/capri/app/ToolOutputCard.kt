package ai.capri.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val ToolLine = Color(0xFF29322B)
private val ToolMuted = Color(0xFF939E94)
private val ToolGreen = Color(0xFFA5FF75)

/** Output stays uncomposed until expanded; folding does not modify the recorded result. */
@Composable internal fun ToolOutputCard(output: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(output) { mutableStateOf(false) }
    val name = remember(output) { output.substringBefore('\n') }
    val body = remember(output) { output.substringAfter('\n', "") }
    val json = remember(body) { runCatching { JSONObject(body) }.getOrNull() }
    val label = remember(name, json) {
        when (name) {
            "android" -> json?.optString("action")?.let { ai.capri.app.nativebridge.NativeCatalog.find(it)?.title } ?: "Android 原生工具"
            "python_run" -> "运行 Python"
            "list_files" -> "查看工作区"
            "read_file" -> "读取文件"
            "write_file" -> "写入文件"
            else -> name
        }
    }
    val status = json?.optString("status").orEmpty()
    val failed = status in setOf("error", "restricted", "permission_denied", "invalid_argument", "unsupported", "timeout", "unavailable") || body.startsWith("工具失败") || (name == "python_run" && body.startsWith("exit=") && !body.startsWith("exit=0\n"))
    val summary = when (status) {
        "partial" -> "返回部分信息"
        "permission_required" -> "需要授权"
        "foreground_required" -> "需要返回应用"
        "setting_required", "user_action_required" -> "需要继续操作"
        "busy" -> "等待处理"
        else -> if (failed) "调用未成功" else "已完成"
    }
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().border(1.dp, ToolLine, RoundedCornerShape(10.dp)).background(Color(0xFF111612), RoundedCornerShape(10.dp)).animateContentSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Terminal, null, tint = if (failed) Color(0xFFFF514F) else ToolGreen, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(label, fontSize = 13.sp, color = Color(0xFFF4F6F3))
                Spacer(Modifier.height(3.dp))
                Text("$summary · ${if (expanded) "点击收起" else "点击展开"}", fontSize = 11.sp, color = ToolMuted)
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = ToolMuted, modifier = Modifier.size(20.dp))
        }
        if (expanded) {
            HorizontalDivider(color = ToolLine)
            val displayed = remember(body) { json?.toString(2) ?: body }
            SelectionContainer {
                Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(14.dp)) {
                    Text(displayed, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, color = ToolMuted)
                }
            }
            TextButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Capri工具输出", body)) }, modifier = Modifier.padding(horizontal = 6.dp)) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("复制输出", fontSize = 11.sp)
            }
        }
    }
}
