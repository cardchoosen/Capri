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
    val personal = vm.sessions.first { it.id == chat }.kind == ai.capri.app.SessionKind.PERSONAL
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
            if (!personal) PersonalMemoryResetControl(vm, !busy)
            TabRow(selectedTabIndex = tab) { listOf("上下文", if (personal) "Capri记忆" else "任务资料", "文档检索").forEachIndexed { i, name -> Tab(selected = tab == i, onClick = { tab = i; result = "" }, text = { Text(name) }) } }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(4.dp))
                if (tab == 0) {
                    Text("按模型能力自动计算上下文预算，预留输出和安全余量；token为估算并由实际用量校准。超预算归档完整工具组，不截断系统规则；单组过大将停止并保留检查点。")
                    Text(budget, style = MaterialTheme.typography.bodySmall)
                    Text("历史摘录摘要", style = MaterialTheme.typography.titleMedium); Text(summary)
                    OutlinedTextField(query, { query = it }, label = { Text("搜索当前会话历史") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && !vm.resettingMemory && !vm.memoryResetRequired && query.isNotBlank(), onClick = { val q = query; val entries = vm.sessions.first { it.id == chat }.messages.toList(); perform { vm.contextHistory.query(chat, entries, JSONObject().put("query", q)) } }) { Text("检索历史") }
                } else if (tab == 1 && personal) {
                    Text("一起聊过的事情会留下记忆，重要的偏好与纠正也会逐渐延续。任务对话单独保存，Capri可以按需回看。")
                    Text("这里只保留整体重置。重置会清空共同经历，核心人格保留。", style = MaterialTheme.typography.bodySmall)
                    PersonalMemoryResetControl(vm, !busy)
                } else {
                    Text("资料独立于核心人格。默认当前会话；明确共享后才可被其他会话检索。停用或删除会停止后续检索；已发送的聊天不会被抹除。", style = MaterialTheme.typography.bodySmall)
                    if (tab == 1) {
                        OutlinedTextField(text, { text = it }, label = { Text(if (editing.isBlank()) "新增记忆" else "编辑记忆") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                        Row { Checkbox(memoryGlobal, { memoryGlobal = it }); Text("跨会话共享", Modifier.padding(top = 12.dp)) }
                        Button(enabled = !busy && !vm.resettingMemory && !vm.memoryResetRequired && text.isNotBlank(), onClick = { val t = text; val g = memoryGlobal; val id = editing
                            perform(onSuccess = { text = ""; editing = ""; memoryGlobal = false }) { vm.knowledge.putMemory(chat, t, "用户在知识面板明确录入/编辑 · ${System.currentTimeMillis()}", g, id); "记忆已保存" }
                        }) { Text("保存记忆") }
                    } else {
                        Text("支持UTF-8：txt/md/csv/json/jsonl/log/yaml/xml/html，单份≤2MB。PDF、Office、图片暂不支持。资料为带SHA-256和行号的快照；原文件修改后需重新索引。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(path, { path = it }, label = { Text("当前工作区相对路径") }, modifier = Modifier.fillMaxWidth())
                        Row { Checkbox(documentGlobal, { documentGlobal = it }); Text("跨会话共享此文档", Modifier.padding(top = 12.dp)) }
                        Button(enabled = !busy && !vm.resettingMemory && !vm.memoryResetRequired && path.isNotBlank(), onClick = { val p = path; val g = documentGlobal; perform { vm.knowledge.index(chat, p, g).let { "索引完成：${it.getString("title")} · ${it.getJSONArray("chunks").length()}段" } } }) { Text("建立 / 更新索引") }
                    }
                    OutlinedTextField(query, { query = it }, label = { Text("搜索记忆与文档") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && !vm.resettingMemory && !vm.memoryResetRequired && query.isNotBlank(), onClick = { val q = query; perform { vm.knowledge.search(chat, q).toString(2) } }) { Text("检索资料") }
                    records.filter { it.optString("kind") == if (tab == 1) "memory" else "document" }.forEach { record ->
                        HorizontalDivider()
                        Text(record.getString("title"), style = MaterialTheme.typography.titleSmall)
                        Text("${if (record.getString("scope") == "global") "跨会话共享" else "当前会话"} · ${record.getString("source")}\nID: ${record.getString("id")}", style = MaterialTheme.typography.bodySmall)
                        if (tab == 1) Text(record.getString("text"))
                        else Text("SHA-256: ${record.optString("sha256")}\n${record.getJSONArray("chunks").length()}段 · ${record.getLong("updated")}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(enabled = !busy && !vm.resettingMemory && !vm.memoryResetRequired, onClick = { perform { vm.knowledge.change(chat, record.getString("id"), !record.getBoolean("enabled")); "启用状态已更新" } }) { Text(if (record.getBoolean("enabled")) "停用" else "启用") }
                            if (tab == 1) TextButton(enabled = !busy && !vm.resettingMemory && !vm.memoryResetRequired, onClick = { editing = record.getString("id"); text = record.getString("text"); memoryGlobal = record.getString("scope") == "global" }) { Text("编辑") }
                            TextButton(enabled = !busy && !vm.resettingMemory && !vm.memoryResetRequired, onClick = { deleting = record }) { Text("删除") }
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

@Composable private fun PersonalMemoryResetControl(vm: CapriModel, available: Boolean) {
    var confirm by remember { mutableStateOf(false) }
    OutlinedButton(enabled = available && !vm.resettingMemory && vm.jobs.isEmpty() && !vm.runtimeBusy && !vm.migrating,
        onClick = { confirm = true }) { Text(if (vm.memoryResetRequired) "重试重置共同经历" else "重置共同经历", color = MaterialTheme.colorScheme.error) }
    if (vm.memoryResetStatus.isNotBlank()) Text(vm.memoryResetStatus, style = MaterialTheme.typography.bodySmall)
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("重置共同经历？") },
        text = { Text(ai.capri.app.SharedMemoryReset.DESCRIPTION) },
        confirmButton = { TextButton(onClick = { confirm = false; vm.resetSharedMemory() }) { Text("清空全部共同经历", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
