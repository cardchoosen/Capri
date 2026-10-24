package ai.capri.app.extensions

import ai.capri.app.CapriModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable internal fun ExtensionsPanel(vm: CapriModel, onDismiss: () -> Unit) {
    val manager = vm.extensions
    val chat = vm.selected
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var category by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var editingExisting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var skillBody by remember { mutableStateOf<String?>(null) }
    val mcpScroll = rememberScrollState()
    val skillsScroll = rememberScrollState()
    val modelRevision by vm.updates.collectAsState()
    val configs = remember(revision, modelRevision) { manager.store.configs() }
    val skills = remember(revision, modelRevision) { manager.store.skills() }
    fun action(block: suspend () -> String) {
        if (busy) return
        job = scope.launch { busy = true; status = "处理中…"
            try { status = withContext(Dispatchers.IO) { block() } }
            catch (cancel: CancellationException) { status = "已取消"; throw cancel }
            catch (e: Exception) { status = e.message?.take(500) ?: "操作未完成" }
            finally { busy = false; revision++; vm.refresh() }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action {
            context.contentResolver.openInputStream(uri).use { raw ->
                requireNotNull(raw)
                val input = java.io.BufferedInputStream(raw); input.mark(4)
                val zip = input.read() == 0x50 && input.read() == 0x4b; input.reset()
                "已导入技能：${manager.store.importSkill(input, zip)}"
            }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("MCP / Skills", fontSize = 21.sp)
                Text("配置全局共享 · MCP连接属于当前会话", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = category == 0, onClick = { category = 0 }, label = { Text("MCP · ${configs.size}") })
                    FilterChip(selected = category == 1, onClick = { category = 1 }, label = { Text("Skills · ${skills.size}") })
                }
                Column(Modifier.weight(1f).verticalScroll(if (category == 0) mcpScroll else skillsScroll)) {
                    if (category == 0) {
                        Text("默认模板尚未连接；启用后可由Capri按任务激活。支持stdio / Streamable HTTP；协议2025-03-26、2024-11-05。", fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy && vm.jobs.isEmpty(), onClick = { editingExisting = false; editing = JSONObject("""{"id":"my-server","name":"自定义远端MCP","type":"remote","url":"https://example.com/mcp","enabled":false,"timeout_seconds":60}""") }) { Text("添加远端") }
                            TextButton(enabled = !busy && vm.jobs.isEmpty(), onClick = { editingExisting = false; editing = JSONObject("""{"id":"my-local","name":"自定义本地MCP","type":"local","command":["python","-m","your_mcp_module"],"python_packages":[],"npm_packages":[],"enabled":false,"timeout_seconds":60}""") }) { Text("添加本地") }
                        }
                        configs.forEach { config ->
                            val id = config.getString("id")
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                                    Text(config.getString("name"), color = MaterialTheme.colorScheme.primary)
                                    Text("$id · ${config.getString("type")}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                Switch(checked = config.optBoolean("enabled"), enabled = !busy && vm.jobs.isEmpty(), onCheckedChange = { enabled -> action { manager.disconnectConfig(id); manager.store.saveConfig(JSONObject(config.toString()).put("enabled", enabled)); if (!enabled) manager.store.setActive(chat, id, false); "已${if (enabled) "启用" else "停用"} $id" } })
                            }
                            Text(if (config.getString("type") == "remote") config.getString("url") else config.getJSONArray("command").toString(), fontSize = 11.sp)
                            Text(config.optString("description"), fontSize = 12.sp)
                            Text("凭据：${if (manager.hasCredential(config)) "已保存" else "未绑定"} · ${manager.state(chat, id)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(enabled = !busy && vm.jobs.isEmpty(), onClick = { editingExisting = true; editing = config }) { Text("配置") }
                                TextButton(enabled = !busy && config.optBoolean("enabled"), onClick = { action { manager.activate(chat, id); manager.state(chat, id) } }) { Text("连接测试") }
                                TextButton(enabled = !busy, onClick = { action { manager.deactivate(chat, id); "已断开当前会话" } }) { Text("断开") }
                            }
                        }
                    } else {
                        Text("仅名称与简介参与发现，正文和资源按需读取。支持标准SKILL.md或单技能ZIP；也可让Capri从工作区安装。导入后不自动执行脚本。", fontSize = 12.sp)
                        TextButton(enabled = !busy && vm.jobs.isEmpty(), onClick = { importer.launch(arrayOf("*/*")) }) { Text("导入 Skill / ZIP") }
                        skills.forEach { skill ->
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f).padding(top = 12.dp)) { Text(skill.name, color = MaterialTheme.colorScheme.primary); Text(if (skill.builtin) "内置" else "用户导入", fontSize = 11.sp) }
                                Switch(checked = manager.store.enabled(skill), enabled = !busy && vm.jobs.isEmpty(), onCheckedChange = { manager.store.enable(skill, it); revision++ })
                            }
                            Text(skill.description, fontSize = 12.sp)
                            Row {
                                TextButton(onClick = { skillBody = skill.directory.resolve("SKILL.md").readText() }) { Text("查看指令") }
                                if (!skill.builtin) TextButton(enabled = !busy && vm.jobs.isEmpty(), onClick = { action { manager.store.removeSkill(skill.id); "已删除技能，已生成的会话产物保留" } }) { Text("删除") }
                            }
                        }
                    }
                }
                if (status.isNotBlank()) Text(status, Modifier.padding(vertical = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (busy) TextButton(onClick = { job?.cancel() }) { Text("取消操作") }
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
    editing?.let { original ->
        var text by remember(original) { mutableStateOf(original.toString(2)) }
        var token by remember(original) { mutableStateOf("") }
        var replaceSecret by remember(original) { mutableStateOf(false) }
        var error by remember(original) { mutableStateOf("") }
        var saving by remember(original) { mutableStateOf(false) }
        val originalId = original.getString("id")
        Dialog(onDismissRequest = { if (!saving) editing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(12.dp)) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("MCP 配置", fontSize = 20.sp)
                    Text("command为参数数组；本地路径可用\${extension}、\${workspace}。依赖按需安装到扩展专属目录。tools可选，用原工具名筛选。", fontSize = 11.sp)
                    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp), label = { Text("配置 JSON") }, enabled = !saving)
                    OutlinedTextField(value = token, onValueChange = { token = it; replaceSecret = true }, label = { Text("Token / API Key（留空保留原值）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !saving)
                    Row {
                        TextButton(enabled = !saving, onClick = { token = ""; replaceSecret = true }) { Text("移除已存凭据") }
                        if (originalId == "github") TextButton(enabled = !saving, onClick = {
                            if (vm.gitAccounts.list().none { it.host == "github.com" }) error = "尚无GitHub全局账号" else { runCatching { text = JSONObject(text).put("credential", "github-account").toString(2); token = ""; replaceSecret = false; error = "已选择全局GitHub凭据引用；更新账号后自动使用新Token" }.onFailure { error = "请先修正配置JSON" } }
                        }) { Text("使用全局GitHub账号") }
                    }
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Row {
                        TextButton(enabled = !saving, onClick = {
                            scope.launch { saving = true
                                try { withContext(Dispatchers.IO) {
                                    val raw = JSONObject(text); if (replaceSecret) raw.put("credential", "stored")
                                    val config = ExtensionPolicy.config(raw)
                                    check(vm.jobs.isEmpty()) { "请等待会话任务结束后修改全局配置" }
                                    if (editingExisting) require(config.getString("id") == originalId) { "已有配置ID不可更改" }
                                    else require(manager.store.configs().none { it.getString("id") == config.getString("id") }) { "同名配置已存在，请修改ID或编辑已有配置" }
                                    manager.disconnectConfig(originalId)
                                    manager.store.saveConfig(config)
                                    if (replaceSecret) manager.store.saveSecret(config.getString("id"), token)
                                }; revision++; editing = null }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (e: Exception) { error = e.message?.take(500).orEmpty() }
                                finally { saving = false; vm.refresh() }
                            }
                        }) { Text("保存") }
                        TextButton(enabled = !saving, onClick = { editing = null }) { Text("取消") }
                        TextButton(enabled = !saving && editingExisting, onClick = { action { manager.disconnectConfig(originalId); manager.store.removeConfig(originalId); "已删除配置" }; editing = null }) { Text("删除配置") }
                    }
                }
            }
        }
    }
    skillBody?.let { body -> AlertDialog(onDismissRequest = { skillBody = null }, title = { Text("Skill 指令") }, text = { Text(body, Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp) }, confirmButton = { TextButton(onClick = { skillBody = null }) { Text("关闭") } }) }
}
