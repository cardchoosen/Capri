package ai.capri.app.browser

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Full-size resident engine views sit behind the opaque conversation/file surface. */
@Composable internal fun BrowserCanvas(browser: BrowserController, chat: String, displayedPage: String?, modifier: Modifier = Modifier) {
    browser.ui.collectAsState().value
    val owner = LocalLifecycleOwner.current
    DisposableEffect(browser, owner) {
        fun sync() { browser.hostResumed(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val listener = LifecycleEventObserver { _, _ -> sync() }
        owner.lifecycle.addObserver(listener); sync()
        onDispose { owner.lifecycle.removeObserver(listener); browser.hostResumed(false) }
    }
    Box(modifier) {
        browser.liveViews(chat).sortedBy { if (it.first == displayedPage) 1 else 0 }.forEach { (id, web) -> key(id, web) {
            val interactive = id == displayedPage
            AndroidView(factory = { context -> BrowserViewHost(context) }, update = { host ->
                host.interactive = interactive
                host.onUserInteraction = { browser.userTakeover(id) }
                host.importantForAccessibility = if (interactive) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                web.importantForAccessibility = if (interactive) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                web.isFocusable = interactive; web.isFocusableInTouchMode = interactive
                if (!interactive) web.clearFocus()
                if (web.parent !== host) {
                    (web.parent as? android.view.ViewGroup)?.removeView(web)
                    host.removeAllViews(); host.addView(web, FrameLayout.LayoutParams(-1, -1))
                }
            }, modifier = Modifier.fillMaxSize())
        } }
    }
}
private class BrowserViewHost(context: Context) : FrameLayout(context) {
    var interactive = false
    var onUserInteraction: () -> Unit = {}
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(!interactive)return false
        if(event.actionMasked == MotionEvent.ACTION_DOWN) onUserInteraction()
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if(!interactive)return false
        if(event.action == android.view.KeyEvent.ACTION_DOWN) onUserInteraction()
        return super.dispatchKeyEvent(event)
    }
}

@Composable internal fun BrowserControls(browser: BrowserController, chat: String, page: String?, onOpened: (String) -> Unit) {
    val state by browser.ui.collectAsState()
    val scope = rememberCoroutineScope()
    val record = browser.tabList(chat).firstOrNull { it.id == page }
    var address by remember(chat, page) { mutableStateOf(record?.url.orEmpty()) }
    var message by remember(chat, page) { mutableStateOf("") }
    var working by remember(chat, page) { mutableStateOf(false) }
    val supported = remember(browser) { browser.status().optBoolean("multi_profile") }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(address, { address = it }, singleLine = true, placeholder = { Text("HTTPS网址", fontSize = 12.sp) }, modifier = Modifier.weight(1f))
            TextButton(enabled = !working && supported && address.isNotBlank(), onClick = {
                working = true
                scope.launch {
                    try {
                        val result = JSONObject(browser.execute(chat, JSONObject().put("action", "open").put("url", address)))
                        if (result.optString("status") == "ok") onOpened(result.getString("page_id"))
                        else message = result.optString("message", result.optString("status"))
                    } finally { working = false }
                }
            }) { Text("打开", fontSize = 12.sp) }
            if (page != null) TextButton(enabled = !working, onClick = {
                scope.launch {
                    working = true
                    try {
                        val result = JSONObject(browser.execute(chat, JSONObject().put("action", "show").put("page_id", page)))
                        message = if (result.optString("status") == "ok") "页面已就绪" else result.optString("message", result.optString("status"))
                    } finally { working = false }
                }
            }) { Text("读取", fontSize = 12.sp) }
        }
        if (page != null && browser.isPaused(page)) Row(Modifier.padding(horizontal = 8.dp)) {
            Text("手动接管中", fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { browser.userResume(page) }) { Text("恢复自动操作", fontSize = 11.sp) }
        }
        if (!supported) Text("系统WebView不支持独立登录环境，请更新WebView", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        val notice = if (state.chat == chat && state.page == page) state.notice.ifBlank { message } else message
        if (notice.isNotBlank()) Text(notice, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
    }
}
