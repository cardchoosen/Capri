package ai.capri.app.retrieval

import ai.capri.app.CapriModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun EnhancementsPanel(vm: CapriModel, onDismiss: () -> Unit) {
    val updates by vm.updates.collectAsState()
    val config = remember(updates) { vm.store.config() }
    val supported = ProviderCapabilities.deepSeek(config)
    var enabled by remember { mutableStateOf(vm.enhancements.enabled(ProviderCapabilities.DEEPSEEK_WEB)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("增强能力") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("按项启用供应商增强。关闭时仍可使用免费基础检索。", style = MaterialTheme.typography.bodySmall)
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
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
