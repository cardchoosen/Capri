package ai.capri.app.nativebridge

import ai.capri.app.CapriModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable internal fun RootPanel(vm: CapriModel) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(vm.androidTools.rootStatus()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val updates by vm.updates.collectAsState()
    LaunchedEffect(updates) { state = vm.androidTools.rootStatus() }
    val authorization = when (state.optString("app_authorization")) {
        "granted" -> "上次已验证授权"; "denied", "denied_or_unavailable" -> "已拒绝"
        "timeout" -> "验证超时"; "verification_failed" -> "核验失败"; else -> "尚未验证"
    }
    Text("Root · Android宿主", style = MaterialTheme.typography.titleMedium)
    Text("设备：${when (state.optString("device_root")) { "confirmed" -> "已实测Root"; "su_detected" -> "检测到su，待验证"; else -> "未发现su" }} · 本应用：$authorization", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !busy, onClick = { state = vm.androidTools.rootStatus() }) { Text("刷新Root状态") }
        OutlinedButton(enabled = !busy && vm.jobs.isEmpty() && state.optBoolean("su_detected"), onClick = {
            busy = true; notice = "正在请求Root管理器授权并核验身份…"
            scope.launch {
                try {
                    val result = JSONObject(vm.androidTools.execute(JSONObject().put("action", "root_request")))
                    state = vm.androidTools.rootStatus()
                    notice = if (result.optString("status") == "ok") "已验证真实root：uid=0。普通PRoot终端保持原身份。" else result.toString()
                } finally { busy = false }
            }
        }) { Text("请求并验证Root授权") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
}
