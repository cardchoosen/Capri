package ai.capri.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable internal fun EnvironmentPanel(vm: CapriModel) {
    vm.updates.collectAsState().value
    val state = vm.environmentState()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, EnvironmentMaintenance.Action>?>(null) }
    var estimate by remember { mutableStateOf("正在估算…") }
    pending?.let { (chat, action) ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(action.label) },
            text = { Text("会话：${vm.sessions.firstOrNull { it.id == chat }?.title ?: chat}\n\n${action.scope}\n\n$estimate\n维护期间暂停执行新任务；有运行中的终端或脚本时会拒绝操作。") },
            confirmButton = { TextButton(onClick = { pending = null; vm.maintainEnvironment(chat, action) }, enabled = !vm.runtimeBusy) { Text("确认执行") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } })
    }
    var diagnostics by remember { mutableStateOf(false) }
    Spacer(Modifier.height(12.dp))
    Text(state.message, fontSize = 14.sp, color = if (state.phase == "error") Color(0xFFFF514F) else Color(0xFFA5FF75))
    if (state.phase == "preparing" || vm.migrating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
    if (state.phase == "error") {
        Text(state.error, fontSize = 11.sp)
        TextButton(onClick = { vm.prepareSession(vm.selected) }) { Text("重试准备") }
    }
    TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "收起诊断与维护" else "诊断与维护") }
    if (diagnostics) {
        Text("共享 Linux 环境；任务依赖按需准备。会话文件与依赖独立保存。", fontSize = 11.sp)
        OutlinedButton(onClick = { vm.inspectPythonEnvironment() }, enabled = !vm.runtimeBusy && !vm.migrating) { Text("检查执行环境") }
        EnvironmentMaintenance.Action.entries.forEach { action ->
            TextButton(onClick = {
                val chat = vm.selected
                pending = chat to action; estimate = "正在估算…"
                scope.launch {
                    val result = runCatching { EnvironmentMaintenance(vm.getApplication(), vm.store.workspace, vm.runtime).estimate(chat, action) }
                    if (pending == chat to action) estimate = result.fold(
                        { "目标文件内容约 ${EnvironmentMaintenance.formatBytes(it)}（不是磁盘可用空间，执行时重新核算）" },
                        { "无法估算：${it.message}" })
                }
            }, enabled = !vm.runtimeBusy && !vm.migrating && vm.jobs.isEmpty() && vm.runtime.ready && state.phase != "preparing") { Text(action.label) }
        }
        if (vm.runtimeBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.runtimeResult.isNotBlank()) Text(vm.runtimeResult, fontSize = 11.sp)
    }
}
