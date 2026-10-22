package ai.capri.app.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/** An on-demand terminal inspector, not a duplicate chat tool-history feed. */
@Composable internal fun TerminalPanel(manager: TerminalManager, chat: String, onDismiss: () -> Unit) {
    val revision by manager.updates.collectAsState()
    var terminals by remember(chat) { mutableStateOf(emptyList<JSONObject>()) }
    var selected by remember(chat) { mutableStateOf<String?>(null) }
    var command by remember(chat) { mutableStateOf("") }
    var result by remember(chat) { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(chat, revision) {
        terminals = manager.list(chat)
        if (selected == null) selected = terminals.lastOrNull { it.optString("state") in setOf("ready", "running") }?.getString("terminal_id")
    }
    fun invoke(action: String, params: JSONObject = JSONObject()) {
        if (working) return
        working = true
        scope.launch {
            try {
                val response = JSONObject(manager.execute(chat, JSONObject().put("action", action).put("params", params)))
                if (response.optString("status") == "ok") {
                    val data = response.getJSONObject("data")
                    if (action == "open") selected = data.getString("terminal_id")
                    if (action == "delete") selected = null
                    if (action == "run") command = ""
                    result = if (action == "read") data.optString("output").ifEmpty { "暂无输出" } else data.toString(2)
                } else result = response.optString("message", "终端操作失败")
            } finally { working = false }
        }
    }
    val chosen = terminals.firstOrNull { it.optString("terminal_id") == selected }
    val commands = chosen?.optJSONArray("commands")
    val latest = if (commands != null && commands.length() > 0) commands.getJSONObject(commands.length() - 1) else null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("持续终端") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("保留目录与环境 · 关闭面板不停止任务", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            Text("后台可能被系统暂停，可在应用电池设置中允许运行。", fontSize = 11.sp)
            TextButton(onClick = { TerminalBackgroundPolicy.openSettings(context) }) { Text("后台电池设置", fontSize = 11.sp) }
            Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                terminals.forEach { terminal ->
                    val id = terminal.getString("terminal_id")
                    Text("${if (id == selected) ">" else " "} ${terminal.getString("title")} · ${id.take(8)} · ${terminal.getString("state")}",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = if (id == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { selected = id; result = "" }.padding(vertical = 8.dp))
                }
            }
            Row {
                TextButton(onClick = { invoke("open") }, enabled = !working) { Text("新建") }
                TextButton(onClick = { selected?.let { invoke("read", JSONObject().put("terminal_id", it)) } }, enabled = !working && selected != null) { Text("读取输出") }
                TextButton(onClick = { selected?.let { invoke("close", JSONObject().put("terminal_id", it)) } }, enabled = !working && chosen?.optString("state") in setOf("ready", "running")) { Text("关闭终端") }
            }
            OutlinedTextField(command, { command = it }, label = { Text("Shell 命令") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { selected?.let { invoke("run", JSONObject().put("terminal_id", it).put("command", command).put("timeout_seconds", 3600)) } },
                    enabled = !working && command.isNotBlank() && chosen?.optString("state") == "ready") { Text("执行 · 最长1小时") }
                TextButton(onClick = { selected?.let { id -> latest?.let { invoke("cancel", JSONObject().put("terminal_id", id).put("command_id", it.getString("command_id"))) } } },
                    enabled = !working && latest?.optString("state") == "running") { Text("中断", color = MaterialTheme.colorScheme.error) }
            }
            if (chosen?.optString("state") !in setOf("starting", "ready", "running") && chosen != null)
                TextButton(onClick = { invoke("delete", JSONObject().put("terminal_id", selected)) }, enabled = !working) { Text("删除终端历史", color = MaterialTheme.colorScheme.error) }
            if (result.isNotEmpty()) SelectionContainer {
                Text(result, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } })
}
