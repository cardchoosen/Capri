package ai.capri.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable internal fun EnvironmentPanel(vm: CapriModel) {
    // Read here: parent recomposition can skip this panel when the model instance is unchanged.
    vm.updates.collectAsState().value
    val chat = vm.selected
    LaunchedEffect(Unit) { vm.inspectEnvironment() }
    LaunchedEffect(chat, vm.runtimeBusy) {
        if (!vm.runtimeBusy && vm.environmentReportChat != chat) vm.inspectEnvironment()
    }
    var confirm by remember { mutableStateOf<EnvironmentComponent?>(null) }
    var details by remember { mutableStateOf(false) }
    val report = vm.environmentReport.takeIf { vm.environmentReportChat == chat }
    Spacer(Modifier.height(12.dp))
    Text("当前组件", fontSize = 16.sp)
    Text("随当前应用提供 · 不自动联网升级", fontSize = 11.sp, color = Color(0xFF939E94))
    report?.let { status ->
        for (component in status.components) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color(0xFF29322B))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(component.title + " · " + component.state, fontSize = 13.sp,
                        color = if (component.valid) Color(0xFFA5FF75) else Color(0xFFFF514F))
                    Text(component.version, fontSize = 11.sp)
                    Text(environmentSize(component.bytes), fontSize = 11.sp, color = Color(0xFF939E94))
                }
                TextButton(onClick = { confirm = component }, enabled = !vm.runtimeBusy) {
                    Text(if (component.state == "未初始化" || component.state == "待迁移") "部署" else "修复")
                }
            }
            if (component.state == "检测到差异" || component.state == "需更新" || component.state == "待迁移")
                Text(component.detail, fontSize = 11.sp, color = Color(0xFFFF514F))
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color(0xFF29322B))
        Text("当前会话：文件 ${environmentSize(status.workspace)} · 依赖 ${environmentSize(status.dependencies)} · 缓存 ${environmentSize(status.cache)}", fontSize = 11.sp)
        if (status.legacy > 0) Text("旧工具目录 ${environmentSize(status.legacy)}，部署成功后清理。", fontSize = 11.sp, color = Color(0xFFFF514F))
        if (status.active.isNotEmpty()) Text("使用中：" + status.active.joinToString { use ->
            (vm.sessions.firstOrNull { it.id == use.chat }?.title ?: use.chat) + " / " + use.kind
        } + "。请先关闭持续终端再修复。", fontSize = 11.sp, color = Color(0xFFFF514F))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.inspectEnvironment(false) }, enabled = !vm.runtimeBusy, shape = RoundedCornerShape(2.dp)) { Text("刷新状态") }
        Button(onClick = { vm.inspectEnvironment(true) }, enabled = !vm.runtimeBusy, shape = RoundedCornerShape(2.dp)) { Text(if (vm.runtimeBusy) "处理中…" else "检查环境") }
    }
    if (vm.runtimeBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(vm.environmentMessage, fontSize = 11.sp, color = Color(0xFF939E94))
    report?.runtimeSummary?.takeIf { it.isNotBlank() }?.let {
        Text(it, fontSize = 12.sp, color = if (it == "运行检查通过") Color(0xFFA5FF75) else Color(0xFFFF514F))
    }
    report?.probe?.takeIf { it.isNotBlank() }?.let { probe ->
        TextButton(onClick = { details = !details }) { Text(if (details) "收起运行诊断" else "查看运行诊断") }
        if (details) Text(probe, fontSize = 11.sp)
    }
    confirm?.let { component ->
        AlertDialog(onDismissRequest = { confirm = null }, shape = RoundedCornerShape(2.dp),
            title = { Text("重新部署${component.title}？") },
            text = { Text("使用当前应用内置资源替换此组件，并清理旧工具目录。保留聊天、工作文件、会话依赖、SSH密钥和Git账号。\n\n" +
                (if (component.id == "base") "基础系统目录中的手工修改会被覆盖；已有依赖不删除，但可能需检查兼容性。\n\n" else "") +
                "有终端或脚本正在使用环境时会拒绝操作，不自动中断任务。") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.repairEnvironment(component.id) }) { Text("确认重新部署") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
}
internal fun environmentSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(bytes.toDouble() / (1024 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes.toDouble() / (1024 * 1024))
    bytes >= 1024 -> "%.1f KiB".format(bytes.toDouble() / 1024)
    else -> "$bytes B"
}
