package ai.capri.app.browser

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable internal fun BrowserPanel(browser: BrowserController) {
    val ui by browser.ui.collectAsState()
    if (!ui.shown) return
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val capabilities = remember(browser) { browser.status() }
    var address by remember(ui.chat, ui.page) { mutableStateOf(browser.tabList(ui.chat).firstOrNull { it.id == ui.page }?.url.orEmpty()) }
    var working by remember { mutableStateOf(false) }
    var message by remember(ui.chat) { mutableStateOf("") }
    LaunchedEffect(ui.chat) { browser.execute(ui.chat, JSONObject().put("action", "tabs")) }
    DisposableEffect(owner) {
        fun sync() { browser.hostResumed(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        owner.lifecycle.addObserver(observer); sync()
        onDispose { owner.lifecycle.removeObserver(observer); browser.hostResumed(false) }
    }
    fun invoke(action: String, value: String? = null) {
        if (working) return
        working = true
        scope.launch {
            try {
                val request = JSONObject().put("action", action)
                if (action == "open") request.put("url", address) else value?.let { request.put("page_id", it) }
                val result = JSONObject(browser.execute(ui.chat, request))
                message = if (result.optString("status") == "ok") {
                    result.optJSONObject("observation")?.let { "${it.optString("title")} · ${it.optJSONArray("elements")?.length() ?: 0} 个交互元素${if (it.optBoolean("truncated")) " · 摘要已截断" else ""}" } ?: "已关闭页面"
                } else "${result.optString("status")}：${result.optString("message") }"
            } finally { working = false }
        }
    }
    Dialog(onDismissRequest = { browser.hide() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding().padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("CAPRI / WEB", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
                    TextButton(onClick = { browser.hide() }) { Text("返回对话") }
                }
                Text("当前会话独立登录环境 · ${capabilities.optString("version")}", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
                if (!capabilities.optBoolean("multi_profile")) Text(capabilities.optString("message"), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(address, { address = it }, label = { Text("HTTPS网址") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { invoke("open") }, enabled = !working && address.isNotBlank() && capabilities.optBoolean("multi_profile")) { Text("新标签") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    browser.tabList(ui.chat).forEachIndexed { index, tab ->
                        TextButton(onClick = { invoke("show", tab.id) }, enabled = !working,
                            colors = ButtonDefaults.textButtonColors(contentColor = if (ui.page == tab.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Text("${index + 1} ${tab.title.take(14)}", maxLines = 1)
                        }
                    }
                }
                Row {
                    TextButton(onClick = { ui.page?.let { invoke("observe", it) } }, enabled = !working && ui.page != null) { Text("读取页面") }
                    TextButton(onClick = { ui.page?.let { invoke("close", it) } }, enabled = !working && ui.page != null) { Text("关闭标签", color = MaterialTheme.colorScheme.error) }
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                val notice = ui.notice.ifBlank { message }
                if (notice.isNotBlank()) Text(notice, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(4.dp))
                val web = ui.page?.let(browser::view)
                if (web == null) Box(Modifier.weight(1f).fillMaxWidth()) { Text("输入网址打开页面；当前支持读取与手动浏览。", modifier = Modifier.padding(12.dp), fontSize = 12.sp) }
                else key(ui.page, web) {
                    AndroidView(factory = { (web.parent as? android.view.ViewGroup)?.removeView(web); web }, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}
