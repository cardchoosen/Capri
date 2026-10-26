package ai.capri.app.model

import ai.capri.app.CapriModel
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun ProviderLogo(provider: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resource = remember(provider) { context.resources.getIdentifier("provider_$provider", "drawable", context.packageName) }
    if (resource != 0) Image(painterResource(resource), Providers.get(provider).name, modifier.size(22.dp))
    else Icon(Icons.Outlined.Hub, Providers.get(provider).name, modifier.size(22.dp))
}

@Composable internal fun ModelPicker(vm: CapriModel, chat: String, onManage: () -> Unit) {
    val revision by vm.updates.collectAsState()
    ModelPicker(vm.store.models, chat, vm.jobs.containsKey(chat), revision, vm::refresh, onManage)
}

@Composable internal fun ModelPicker(store: ModelAccounts, chat: String, busy: Boolean, revision: Int, onChange: () -> Unit, onManage: () -> Unit) {
    var open by remember(chat) { mutableStateOf(false) }
    val selected = remember(chat, revision) { store.selected(chat) }
    TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(36.dp)) {
        ProviderLogo(selected?.let { Providers.logo(it.first.provider, it.second.id) } ?: "custom"); Spacer(Modifier.width(8.dp))
        Text(selected?.second?.name ?: "选择模型", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false), fontSize = 12.sp)
        if (selected != null) Text(" · ${contextLabel(selected.second)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        ModelDialog("选择模型", { open = false }) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("搜索模型或 API") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val accounts = store.all()
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 450.dp)) {
                accounts.forEach { account ->
                    val models = account.models.filter { query.isBlank() || (account.name + it.id + it.name).contains(query, true) }
                    if (models.isNotEmpty()) {
                        item("header-${account.id}") { Text(account.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                        items(models, key = { account.id + ":" + it.id }) { model ->
                            Row(Modifier.fillMaxWidth().clickable { store.select(chat, account.id, model.id); onChange(); open = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                ProviderLogo(Providers.logo(account.provider, model.id)); Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) { Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                    if (model.name != model.id) Text(model.id, maxLines = 1, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(contextLabel(model), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                if (selected?.first?.id == account.id && selected.second.id == model.id) Icon(Icons.Outlined.Check, "已选择", Modifier.padding(start = 6.dp).size(16.dp))
                            }
                        }
                    }
                }
                if (accounts.all { it.models.isEmpty() }) item { Text("添加 API 并获取模型列表，也可手动添加模型 ID。", Modifier.padding(vertical = 20.dp)) }
            }
            Text("容量优先读取接口；≈ 表示内置资料参考值，未知则不推测。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) Text("当前任务继续使用原模型，新选择从下一轮生效。", fontSize = 11.sp)
            TextButton(onClick = { open = false; onManage() }) { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Text("管理模型 API") }
        }
    }
}

@Composable private fun ModelDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 680.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭") } }
                content()
            }
        }
    }
}

@Composable internal fun ModelsPanel(vm: CapriModel, onDismiss: () -> Unit) {
    val tick by vm.updates.collectAsState()
    ModelAccountsPanel(vm.store.models, tick, vm::refresh, onDismiss)
}

@Composable internal fun ModelAccountsPanel(store: ModelAccounts, revision: Int, onChange: () -> Unit, onDismiss: () -> Unit) {
    val accounts = remember(revision) { store.all() }
    var editing by remember { mutableStateOf<ModelAccount?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ModelAccount?>(null) }
    if (creating || editing != null) {
        AccountEditor(store, onChange, editing, onDismiss = { creating = false; editing = null })
    } else ModelDialog("模型 API", onDismiss) {
        TextButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, null); Text("添加 API") }
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(accounts, key = { it.id }) { account ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProviderLogo(account.provider); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text(account.name); Text("${account.models.size} 个模型 · ${Providers.get(account.provider).name}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { editing = account }) { Icon(Icons.Outlined.Edit, "编辑 ${account.name}", Modifier.size(18.dp)) }
                    IconButton(onClick = { deleting = account }) { Icon(Icons.Outlined.DeleteOutline, "删除 ${account.name}", Modifier.size(18.dp)) }
                }
            }
            if (accounts.isEmpty()) item { Text("选择厂商，填入 API Key，即可获取模型。", Modifier.padding(vertical = 20.dp)) }
        }
    }
    deleting?.let { account -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${account.name}？") }, text = { Text("使用它的会话需要重新选择模型，聊天和文件保留。已运行任务需另行停止。") },
        confirmButton = { TextButton(onClick = { store.remove(account.id); deleting = null; onChange() }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable private fun AccountEditor(store: ModelAccounts, onChange: () -> Unit, original: ModelAccount?, onDismiss: () -> Unit) {
    var provider by remember { mutableStateOf(Providers.get(original?.provider ?: "deepseek")) }
    var providerMenu by remember { mutableStateOf(false) }
    var protocolMenu by remember { mutableStateOf(false) }
    var protocol by remember { mutableStateOf(original?.protocol ?: provider.protocol) }
    var name by remember { mutableStateOf(original?.name ?: provider.name) }
    var endpoint by remember { mutableStateOf(original?.endpoint ?: provider.url) }
    var key by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(provider.id in setOf("custom", "qwen")) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val context = LocalContext.current
    val catalog = remember { ModelCatalog(context.assets.open("model_catalog.json").bufferedReader().use { it.readText() }) }
    val scope = rememberCoroutineScope(); val api = remember { ModelApi(catalog = catalog) }
    fun account(): ModelAccount {
        require(name.isNotBlank() && name.length <= 80) { "请填写配置名称" }
        val normalizedEndpoint = Providers.endpoint(endpoint)
        val secret = key.trim().ifBlank { original?.takeIf { it.endpoint == normalizedEndpoint && it.provider == provider.id }?.key.orEmpty() }
        require(secret.isNotBlank() && secret.length <= 8192 && secret.none { it == '\r' || it == '\n' }) { "请填写 API Key" }
        val same = original?.endpoint == endpoint.trimEnd('/') && original.protocol == protocol && original.key == secret
        val models = (if (same) original!!.models else emptyList()).toMutableList()
        if (manual.isNotBlank() && models.none { it.id == manual.trim() }) models += ModelOption(manual.trim(), manual = true)
        val account = ModelAccount(id = original?.id ?: java.util.UUID.randomUUID().toString(), provider = provider.id, name = name.trim(), endpoint = normalizedEndpoint, key = secret, protocol = protocol, models = models)
        return account.copy(models = catalog.enrich(account, models))
    }
    ModelDialog(if (original == null) "添加模型 API" else "编辑模型 API", onDismiss) {
        Column(Modifier.weight(1f, fill = false).heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                OutlinedButton(enabled = !busy, onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) { ProviderLogo(provider.id); Text(provider.name, Modifier.weight(1f).padding(start = 10.dp)); Icon(Icons.Outlined.ExpandMore, null) }
                DropdownMenu(providerMenu, { providerMenu = false }, Modifier.heightIn(max = 300.dp)) {
                    Providers.all.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, leadingIcon = { ProviderLogo(item.id) }, onClick = {
                        provider = item; key = ""; manual = ""; protocol = item.protocol; name = item.name; endpoint = item.url; advanced = item.id in setOf("custom", "qwen"); providerMenu = false
                    }) }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("配置名称") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it }, label = { Text(if (original == null) "API Key" else "API Key（同地址留空保留）") }, enabled = !busy, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !busy, onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级选项" else "高级选项") }
            if (advanced) {
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("API Base URL") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Box { OutlinedButton(enabled = !busy, onClick = { protocolMenu = true }) { Text(protocol.label) }
                    DropdownMenu(protocolMenu, { protocolMenu = false }) { ModelProtocol.entries.forEach { p -> DropdownMenuItem(text = { Text(p.label) }, onClick = { protocol = p; protocolMenu = false }) } } }
                if (provider.id == "qwen") Text("百炼地域、业务空间和套餐地址可能不同，请使用控制台对应的 Base URL。", fontSize = 11.sp)
            }
            OutlinedTextField(manual, { manual = it }, label = { Text("手动添加模型 ID（可选）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            if (original != null) Text("已保存 ${original.models.size} 个模型", fontSize = 11.sp)
            if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = !busy, onClick = {
                try { store.save(account()); onChange(); onDismiss() } catch (_: Exception) { status = "请检查名称、HTTPS 地址、API Key 和模型 ID" }
            }) { Text("仅保存") }
            Button(enabled = !busy, onClick = {
                val account = try { account() } catch (_: Exception) { status = "请填写有效 API Key 和 HTTPS 地址"; return@Button }
                busy = true; status = "正在获取模型…"
                scope.launch {
                    try {
                        val models = api.models(account)
                        require(models.isNotEmpty()) { "空列表" }
                        val merged = (models + account.models.filter { old -> old.manual && models.none { it.id == old.id } }).distinctBy { it.id }
                        store.save(account.copy(models = merged)); onChange(); onDismiss()
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { status = ModelApi.errorLabel(error) + "；当前输入已保留" }
                    finally { busy = false }
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("保存并获取模型") }
        }
    }
}

@Composable internal fun ModelBalances(vm: CapriModel) {
    val tick by vm.updates.collectAsState()
    val accounts = remember(tick) { vm.store.models.all().filter { ai.capri.app.retrieval.ProviderCapabilities.deepSeek(it.config(ModelOption("balance"))) } }
    if (accounts.isEmpty()) return
    val api = remember { ModelApi() }; val scope = rememberCoroutineScope()
    val values = remember { mutableStateMapOf<String, String>() }; val fetching = remember { mutableStateMapOf<String, Boolean>() }
    fun identity(account: ModelAccount) = ModelWire.scope(account.config(ModelOption("balance")))
    fun refresh(account: ModelAccount) {
        val id = identity(account)
        if (fetching[id] == true) return
        fetching[id] = true
        values[id] = "正在获取余额…"
        scope.launch {
            try { values[id] = api.balance(account) + " · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date()) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { values[id] = "余额获取失败 · " + ModelApi.errorLabel(error, listing = false) }
            finally { fetching[id] = false }
        }
    }
    LaunchedEffect(accounts) { accounts.forEach { refresh(it) } }
    accounts.forEach { account ->
        val id = identity(account)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ProviderLogo(account.provider); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(account.name, fontSize = 12.sp); Text(values[id] ?: "正在获取余额…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(enabled = fetching[id] != true, onClick = { refresh(account) }) { Icon(Icons.Outlined.Refresh, "刷新 ${account.name} 余额", Modifier.size(18.dp)) }
    } }
}

private fun contextLabel(model: ModelOption) = (if (model.context != null && model.source == "models_registry") "≈" else "") + Providers.contextLabel(model.context)
