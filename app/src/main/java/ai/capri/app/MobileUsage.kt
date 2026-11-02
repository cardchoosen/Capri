package ai.capri.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.capri.app.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable internal fun UsageScreen(vm: CapriModel) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<LocalUsage.Snapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val directories = vm.sessions.map { File(vm.store.workspace.root(it.id), "metrics") }
    fun refresh() {
        if(busy)return
        busy = true; error = false
        scope.launch {
            try { snapshot = withContext(Dispatchers.IO) { LocalUsage.read(directories) } }
            catch(cancel: CancellationException) { throw cancel }
            catch(_: Exception) { error = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeading("用量", "账号余额来自服务商；调用统计来自这台设备保留的记录。")
        SettingsSection("账号余额") { AccountBalances(vm) }
        SettingsSection("最近本机记录") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("最近最多 120 轮对话", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(enabled = !busy, onClick = ::refresh) { Icon(Icons.Outlined.Refresh, "刷新使用记录") }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(error) Text("读取未完成，保留上次结果；请稍后重试。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            snapshot?.let { data ->
                if(data.turns == 0) Text("暂无本机调用记录，开始对话后可在这里查看。", Modifier.padding(16.dp))
                else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("模型请求次数", data.attempts.toString())
                    Metric("输入 Token", if(data.reported > 0) "%,d".format(data.input) else "未上报")
                    Metric("输出 Token", if(data.reported > 0) "%,d".format(data.output) else "未上报")
                    Metric("请求累计耗时", "%.1f 秒".format(data.durationMs / 1000.0))
                    Text("覆盖 ${data.turns} 轮记录；${data.reported} 次响应提供完整 Token 数据。包含重试、上下文整理和回复生成；缺失数据不计为零。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if(data.limited || data.warnings > 0) Text("仅显示可读取的最近记录，部分历史或不完整记录未纳入。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("本机记录会随聊天删除而减少，不代表累计账单，也不推算消费金额。", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.titleMedium) }
}
@Composable internal fun AccountBalances(vm: CapriModel) {
    val revision by vm.updates.collectAsState()
    val accounts = remember(revision) { vm.store.models.all() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("model-balance-cache", Context.MODE_PRIVATE) }
    val api = remember { ModelApi() }; val scope = rememberCoroutineScope()
    val values = remember { mutableStateMapOf<String,String>() }; val dates = remember { mutableStateMapOf<String,Long>() }
    val loading = remember { mutableStateMapOf<String,Boolean>() }; val errors = remember { mutableStateMapOf<String,Boolean>() }
    fun identity(account: ModelAccount) = ModelWire.scope(account.config(ModelOption("balance")))
    fun refresh(account: ModelAccount) {
        val key = identity(account); if(loading[key] == true)return
        loading[key] = true; errors[key] = false
        scope.launch {
            try {
                val value = api.balance(account); val date = System.currentTimeMillis()
                values[key] = value; dates[key] = date
                withContext(Dispatchers.IO) { prefs.edit().putString(key,value).putLong("$key-time",date).commit() }
            } catch(cancel: CancellationException) { throw cancel }
            catch(_: Exception) { errors[key] = true }
            finally { loading[key] = false }
        }
    }
    val identities = accounts.map(::identity)
    LaunchedEffect(identities) {
        accounts.forEach { account ->
            val key = identity(account)
            prefs.getString(key,null)?.let { values[key] = it; dates[key] = prefs.getLong("$key-time",0) }
            if(ai.capri.app.retrieval.ProviderCapabilities.deepSeek(account.config(ModelOption("balance"))) && System.currentTimeMillis() - (dates[key] ?: 0) >= 60_000) refresh(account)
        }
    }
    if(accounts.isEmpty()) Text("尚未配置模型账号，请在设置 → 模型配置中添加。", Modifier.padding(16.dp))
    accounts.forEach { account ->
        val key = identity(account)
        val supported = ai.capri.app.retrieval.ProviderCapabilities.deepSeek(account.config(ModelOption("balance")))
        ListItem(leadingContent = { ProviderLogo(account.provider) }, headlineContent = { Text(account.name) }, supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if(supported) values[key] ?: if(loading[key] == true) "正在获取余额…" else "尚未获取余额" else "该服务暂不支持余额查询")
                dates[key]?.takeIf { it > 0 }?.let { Text("更新于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it))}", style = MaterialTheme.typography.labelSmall) }
                if(errors[key] == true) Text(if(values[key] != null) "刷新失败 · 上次余额已过期，请检查网络或账号后重试" else "查询失败 · 请检查网络或账号后重试", color = MaterialTheme.colorScheme.error)
            }
        }, trailingContent = { if(supported) IconButton(enabled = loading[key] != true, onClick = { refresh(account) }) { Icon(Icons.Outlined.Refresh, "刷新 ${account.name} 余额") } })
    }
}
