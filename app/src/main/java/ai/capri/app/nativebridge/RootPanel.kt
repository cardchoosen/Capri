package ai.capri.app.nativebridge

import ai.capri.app.*
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
    ConfigIntro("高级 · Android 宿主", "Root 授权由设备上的管理器决定；普通终端仍保持原身份。")
    ConfigGroup("授权状态") {
        ConfigFact("设备 Root",when (state.optString("device_root")) { "confirmed" -> "已实测 Root"; "su_detected" -> "检测到 su，待验证"; else -> "未发现 su" })
        ConfigFact("Capri 授权",authorization)
        OutlinedButton(enabled = !busy, onClick = { state = vm.androidTools.rootStatus() },modifier=Modifier.fillMaxWidth()) { Text("刷新状态") }
    }
    ConfigGroup("请求授权", "按下后会打开 Root 管理器并核验真实身份。") {
        OutlinedButton(enabled = !busy && vm.jobs.isEmpty() && state.optBoolean("su_detected"), onClick = {
            busy = true; notice = "正在请求Root管理器授权并核验身份…"
            scope.launch {
                try {
                    val result = JSONObject(vm.androidTools.execute(JSONObject().put("action", "root_request")))
                    state = vm.androidTools.rootStatus()
                    notice = if (result.optString("status") == "ok") "已验证真实root：uid=0。普通PRoot终端保持原身份。" else result.toString()
                } finally { busy = false }
            }
        },modifier=Modifier.fillMaxWidth()) { Text("请求并验证 Root 授权") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    ConfigNotice(notice)
}
