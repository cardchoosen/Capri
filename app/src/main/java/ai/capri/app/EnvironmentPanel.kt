package ai.capri.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun EnvironmentPanel(vm: CapriModel) {
    vm.updates.collectAsState().value
    val state = vm.environmentState()
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
        if (vm.runtimeBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.runtimeResult.isNotBlank()) Text(vm.runtimeResult, fontSize = 11.sp)
    }
}
