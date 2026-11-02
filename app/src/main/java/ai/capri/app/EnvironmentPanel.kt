package ai.capri.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable internal fun EnvironmentPanel(vm: CapriModel, initiallyExpanded: Boolean = false) {
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
    ConfigGroup("环境状态") {
        ConfigFact("当前状态",state.message)
        if (state.phase == "preparing" || vm.migrating) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.phase == "error") {
            ConfigNotice(state.error,true)
            OutlinedButton(onClick = { vm.prepareSession(vm.selected) }) { Text("重试准备") }
        }
    }
    ConfigHelp("诊断与维护", initiallyExpanded) {
        ConfigIntro("当前对话 · ${vm.current().title}", "共享 Linux 环境，任务依赖按需准备。维护操作会先显示影响范围。")
        OutlinedButton(onClick = { vm.inspectPythonEnvironment() }, enabled = !vm.runtimeBusy && !vm.migrating, modifier=Modifier.fillMaxWidth()) { Text("检查执行环境") }
        EnvironmentMaintenance.Action.entries.forEach { action ->
            SettingsRow(action.label, action.scope, onClick = {
                val chat = vm.selected
                pending = chat to action; estimate = "正在估算…"
                scope.launch {
                    val result = runCatching { EnvironmentMaintenance(vm.getApplication(), vm.store.workspace, vm.runtime).estimate(chat, action) }
                    if (pending == chat to action) estimate = result.fold(
                        { "目标文件内容约 ${EnvironmentMaintenance.formatBytes(it)}（不是磁盘可用空间，执行时重新核算）" },
                        { "无法估算：${it.message}" })
                }
            }, enabled = !vm.runtimeBusy && !vm.migrating && vm.jobs.isEmpty() && vm.runtime.ready && state.phase != "preparing")
        }
        if (vm.runtimeBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.runtimeResult.isNotBlank()) ConfigGroup("最近维护结果") { Text(vm.runtimeResult, style=MaterialTheme.typography.bodySmall) }
    }
}
