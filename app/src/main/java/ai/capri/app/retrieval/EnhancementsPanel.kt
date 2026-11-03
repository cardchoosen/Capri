package ai.capri.app.retrieval

import ai.capri.app.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun EnhancementsPanel(vm: CapriModel, onDismiss: () -> Unit) {
    val updates by vm.updates.collectAsState()
    val config = remember(updates) { vm.store.config(vm.selected) }
    val supported = ProviderCapabilities.deepSeek(config)
    var enabled by remember { mutableStateOf(vm.enhancements.enabled(ProviderCapabilities.DEEPSEEK_WEB)) }
    val scope = rememberCoroutineScope()
    val reaEnabled = remember(updates) { vm.extensions.rea.enabled }
    var reaBusy by remember { mutableStateOf(false) }
    var reaMessage by remember { mutableStateOf("") }
    ai.capri.app.DetailPage(onDismissRequest = onDismiss, title = { Text("增强能力") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ConfigIntro("全局 · 能力增强", "按需要启用服务，实际可用性由账号、环境与权限决定。")
            ConfigGroup("联网服务") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DeepSeek 搜索与抓取", Modifier.weight(1f))
                Switch(checked = enabled, enabled = supported || enabled, onCheckedChange = { value ->
                    vm.enhancements.enable(ProviderCapabilities.DEEPSEEK_WEB, value); enabled = value; vm.refresh()
                })
            }
            Text("复用官方 DeepSeek API Key。搜索和抓取各按成功请求额外计费，当前官方价格均为5元/千次；不包含模型推理费用。", style = MaterialTheme.typography.bodySmall)
            Text(if (supported) "已识别官方 DeepSeek 服务；账户权限在实际请求时确认。" else "当前配置不适用。仅支持官方 api.deepseek.com，第三方中转暂不推断此能力。", style = MaterialTheme.typography.bodySmall)
            if (supported) Text(vm.enhancements.deepSeekStatus, style = MaterialTheme.typography.bodySmall)
            }
            ConfigGroup("分析环境") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("REA 逆向分析", Modifier.weight(1f))
                Switch(checked = reaEnabled, enabled = !reaBusy, onCheckedChange = { value ->
                    reaBusy = true; scope.launch {
                        try { vm.extensions.setReaEnabled(vm.selected, value); reaMessage = if(value) "已开启；按任务准备共享分析环境" else "已关闭" }
                        catch (e: Exception) { reaMessage = e.message.orEmpty() }
                        finally { reaBusy = false; vm.refresh() }
                    }
                })
            }
            Text("APK / DEX、JavaScript、原生二进制与运行时分析。工具按任务准备，文件在手机执行；远端模型会收到所选分析片段。", style = MaterialTheme.typography.bodySmall)
            Text(reaMessage.ifBlank { vm.extensions.rea.snapshot().optString("progress") }, style = MaterialTheme.typography.bodySmall)
            if (reaEnabled) Text("开启后直接在对话中提出逆向任务；动态分析需指定目标设备或进程。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
