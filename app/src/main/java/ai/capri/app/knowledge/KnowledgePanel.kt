package ai.capri.app.knowledge

import ai.capri.app.CapriModel
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

@Composable internal fun KnowledgePanel(vm: CapriModel, onDismiss: () -> Unit) {
    val chat = remember { vm.selected }
    val updates by vm.updates.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }; var path by remember { mutableStateOf("") }; var memoryGlobal by remember { mutableStateOf(false) }; var documentGlobal by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf("") }; var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var records by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var summary by remember { mutableStateOf("") }; var budget by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(updates, chat) { withContext(Dispatchers.IO) {
        Triple(vm.knowledge.records(chat), vm.contextHistory.summary(chat), vm.contextHistory.budget(chat))
    }.let { records = it.first; summary = it.second; budget = it.third } }
    fun perform(onSuccess: () -> Unit = {}, block: () -> String) {
        busy = true
        scope.launch {
            try { result = withContext(Dispatchers.IO) { block() }; onSuccess() }
            catch (e: Exception) { result = "未完成：${e.message}" }
            finally { busy = false; vm.refresh() }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("上下文 / 记忆 / 文档", style = MaterialTheme.typography.titleLarge); TextButton(onClick = onDismiss) { Text("关闭") } }
            Text("当前会话 · ${vm.sessions.first { it.id == chat }.title}", style = MaterialTheme.typography.labelSmall)
            TabRow(selectedTabIndex = tab) { listOf("上下文", "长期记忆", "文档检索").forEachIndexed { i, name -> Tab(selected = tab == i, onClick = { tab = i; result = "" }, text = { Text(name) }) } }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(4.dp))
                if (tab == 0) {
                    Text("输入预算96KB（含工具说明的UTF-8字节估算，非精确token数），输出上限4096 tokens。超预算归档完整工具组，不截断系统规则；单组过大将停止并保留检查点。")
                    Text(budget, style = MaterialTheme.typography.bodySmall)
                    Text("历史摘录摘要", style = MaterialTheme.typography.titleMedium); Text(summary)
                    OutlinedTextField(query, { query = it }, label = { Text("搜索当前会话历史") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && query.isNotBlank(), onClick = { val q = query; val entries = vm.sessions.first { it.id == chat }.messages.toList(); perform { vm.contextHistory.query(chat, entries, JSONObject().put("query", q)) } }) { Text("检索历史") }
                } else {
                    Text("资料独立于核心人格。默认当前会话；明确共享后才可被其他会话检索。停用或删除会停止后续检索；已发送的聊天不会被抹除。", style = MaterialTheme.typography.bodySmall)
                    if (tab == 1) {
                        OutlinedTextField(text, { text = it }, label = { Text(if (editing.isBlank()) "新增记忆" else "编辑记忆") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                        Row { Checkbox(memoryGlobal, { memoryGlobal = it }); Text("跨会话共享", Modifier.padding(top = 12.dp)) }
                        Button(enabled = !busy && text.isNotBlank(), onClick = { val t = text; val g = memoryGlobal; val id = editing
                            perform(onSuccess = { text = ""; editing = ""; memoryGlobal = false }) { vm.knowledge.putMemory(chat, t, "用户在知识面板明确录入/编辑 · ${System.currentTimeMillis()}", g, id); "记忆已保存" }
                        }) { Text("保存记忆") }
                    } else {
                        Text("支持UTF-8：txt/md/csv/json/jsonl/log/yaml/xml/html，单份≤2MB。PDF、Office、图片暂不支持。资料为带SHA-256和行号的快照；原文件修改后需重新索引。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(path, { path = it }, label = { Text("当前工作区相对路径") }, modifier = Modifier.fillMaxWidth())
                        Row { Checkbox(documentGlobal, { documentGlobal = it }); Text("跨会话共享此文档", Modifier.padding(top = 12.dp)) }
                        Button(enabled = !busy && path.isNotBlank(), onClick = { val p = path; val g = documentGlobal; perform { vm.knowledge.index(chat, p, g).let { "索引完成：${it.getString("title")} · ${it.getJSONArray("chunks").length()}段" } } }) { Text("建立 / 更新索引") }
                    }
                    OutlinedTextField(query, { query = it }, label = { Text("搜索记忆与文档") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && query.isNotBlank(), onClick = { val q = query; perform { vm.knowledge.search(chat, q).toString(2) } }) { Text("检索资料") }
                    records.filter { it.optString("kind") == if (tab == 1) "memory" else "document" }.forEach { record ->
                        HorizontalDivider()
                        Text(record.getString("title"), style = MaterialTheme.typography.titleSmall)
                        Text("${if (record.getString("scope") == "global") "跨会话共享" else "当前会话"} · ${record.getString("source")}\nID: ${record.getString("id")}", style = MaterialTheme.typography.bodySmall)
                        if (tab == 1) Text(record.getString("text"))
                        else Text("SHA-256: ${record.optString("sha256")}\n${record.getJSONArray("chunks").length()}段 · ${record.getLong("updated")}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(enabled = !busy, onClick = { perform { vm.knowledge.change(chat, record.getString("id"), !record.getBoolean("enabled")); "启用状态已更新" } }) { Text(if (record.getBoolean("enabled")) "停用" else "启用") }
                            if (tab == 1) TextButton(enabled = !busy, onClick = { editing = record.getString("id"); text = record.getString("text"); memoryGlobal = record.getString("scope") == "global" }) { Text("编辑") }
                            TextButton(enabled = !busy, onClick = { deleting = record }) { Text("删除") }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (result.isNotBlank()) { HorizontalDivider(); Text(result, style = MaterialTheme.typography.bodySmall) }
            }
        } }
    }
    deleting?.let { record -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除${record.getString("title")}？") }, text = { Text("删除索引/记忆后不再检索。原文件与既有聊天保留。共享条目会对所有会话生效。") }, confirmButton = { TextButton(onClick = { deleting = null; perform { vm.knowledge.change(chat, record.getString("id")); "已删除" } }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
