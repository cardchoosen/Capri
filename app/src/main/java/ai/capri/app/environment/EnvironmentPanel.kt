package ai.capri.app.environment

import ai.capri.app.CapriModel
import ai.capri.app.MarkdownReply
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun InstallationEnvironmentPanel(vm: CapriModel) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var offset by remember { mutableIntStateOf(0) }
    var next by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load(position: Int) {
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { JSONObject(vm.installationEnvironment.document(JSONObject().put("query", query).put("offset", position))) }
                text = result.getString("text"); next = if (result.isNull("next_offset")) null else result.getInt("next_offset"); offset = position
            } catch (e: Exception) { text = "档案读取未完成：${e.message}" }
            finally { busy = false }
        }
    }
    OutlinedButton(onClick = { open = true; load(0) }) { Text("安装环境档案") }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("安装环境档案", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { open = false }) { Text("关闭") }
            }
            Text("本安装共享 · 自动维护Markdown · 观察、验证与适用环境分开记录", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it }, label = { Text("筛选主题或能力") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row {
                TextButton(enabled = !busy, onClick = { load(0) }) { Text("查询 / 重新读取") }
                TextButton(enabled = !busy && offset > 0, onClick = { load((offset - 16000).coerceAtLeast(0)) }) { Text("上一页") }
                TextButton(enabled = !busy && next != null, onClick = { next?.let(::load) }) { Text("下一页") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { MarkdownReply(text) }
        } }
    }
}
