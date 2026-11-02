package ai.capri.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import org.json.JSONObject

/** Reads the same scoped receipts and metadata stream used by tools and Web. */
@Composable internal fun AgentReliabilityPanel(vm:CapriModel,session:Session) {
    var open by remember(session.id,session.contextVersion) { mutableStateOf(false) }
    var data by remember { mutableStateOf("") };var error by remember { mutableStateOf("") }
    fun load() { runCatching {
        val scope=HistoryEditing.actualContextKey(session)
        JSONObject().put("jobs",vm.durableJobs.list(scope)).put("events",vm.taskEvents.read(scope)).toString(2)
    }.onSuccess { data=it;error="" }.onFailure { error="任务记录暂不可读，请保留现场后重试。" } }
    TextButton(onClick={load();open=true}) { Text("持久任务与执行来源") }
    if(open)AlertDialog(onDismissRequest={open=false},title={Text("持久任务与执行来源")},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
            Text("后台首版支持已授权的只读文件分析。可在对话中让 Capri 保存目标、查看结果、暂停或取消任务。")
            if(error.isNotBlank())Text(error)
            SelectionContainer { Text(data) }
        }
    },confirmButton={TextButton(onClick={load()}){Text("刷新")}},dismissButton={TextButton(onClick={open=false}){Text("关闭")}})
}
