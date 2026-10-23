package ai.capri.app.preview

import ai.capri.app.Workspace
import ai.capri.app.browser.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Navigation is owned exclusively by PreviewTabs; engine events only add/update tabs. */
@Composable internal fun WorkspaceSurface(tabs: PreviewTabs, browser: BrowserController, workspace: Workspace, chat: String,
    active: Boolean, modifier: Modifier = Modifier, onExport: (String) -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    tabs.updates.collectAsState().value
    val engine by browser.ui.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val items = tabs.tabs(chat)
    val chosen = if (active) tabs.current(chat) else null
    LaunchedEffect(browser, chat) { browser.execute(chat, JSONObject().put("action", "tabs")) }
    Column(modifier) {
        if (active && items.isNotEmpty()) Row(Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surface).semantics { contentDescription = "会话子标签" }, verticalAlignment = Alignment.CenterVertically) {
            fun select(id: String) {
                focus.clearFocus(); keyboard?.hide()
                tabs.select(chat, id)
                items.firstOrNull { it.id == id && it.kind == PreviewKind.WEB }?.let { item ->
                    if (browser.view(item.target) == null) scope.launch { browser.execute(chat, JSONObject().put("action", "show").put("page_id", item.target)) }
                }
            }
            Text("对话", fontSize = 12.sp, color = if (chosen == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { select(PreviewTabs.CHAT) }.semantics { contentDescription = "预览：对话"; stateDescription = if (chosen == null) "已选中" else "未选中" }.padding(horizontal = 14.dp, vertical = 8.dp))
            items.forEach { item ->
                Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title.take(24), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                        color = if (chosen?.id == item.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 160.dp).clickable { select(item.id) }
                            .semantics { contentDescription = "预览：${item.title}"; stateDescription = if (chosen?.id == item.id) "已选中" else "未选中" }.padding(horizontal = 8.dp, vertical = 8.dp))
                    if (item.kind == PreviewKind.WEB && engine.busy && engine.page == item.target) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.dp)
                    Icon(Icons.Outlined.Close, "关闭预览：${item.title}", modifier = Modifier.size(28.dp).clickable {
                        if (item.kind == PreviewKind.WEB) browser.closePreview(chat, item.target) else tabs.close(chat, item.id)
                    }.padding(6.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (chosen?.kind in setOf(PreviewKind.WEB, PreviewKind.WEB_HOME)) BrowserControls(browser, chat,
            chosen?.takeIf { it.kind == PreviewKind.WEB }?.target, onOpened = { id ->
                // This callback follows an explicit user 'Open', never an agent call.
                tabs.close(chat, PreviewTabs.HOME); tabs.select(chat, PreviewTabs.web(id))
            })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BrowserCanvas(browser, chat, chosen?.takeIf { it.kind == PreviewKind.WEB }?.target, Modifier.fillMaxSize())
            when (chosen?.kind) {
                PreviewKind.WEB -> if (browser.view(chosen.target) == null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Text("页面尚未就绪，点击读取可重新准备。", fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                }
                PreviewKind.WEB_HOME -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Text("输入网址打开网页。Capri操作网页时不会切换你的预览。", fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                }
                PreviewKind.FILE -> key(chat, chosen.id) { FilePreview(tabs, workspace, chat, chosen, onExport) }
                null -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), content = content)
            }
        }
    }
}

@Composable private fun FilePreview(tabs: PreviewTabs, workspace: Workspace, chat: String, tab: PreviewTab, onExport: (String) -> Unit) {
    var body by remember { mutableStateOf("正在读取…") }
    val vertical = rememberScrollState(tabs.position(chat, tab.id))
    val horizontal = rememberScrollState()
    DisposableEffect(chat, tab.id) { onDispose { tabs.rememberPosition(chat, tab.id, vertical.value) } }
    LaunchedEffect(chat, tab.target) {
        body = withContext(Dispatchers.IO) {
            runCatching {
                PreviewText.read(workspace.file(chat, tab.target))
            }.getOrElse { "无法预览：${it.message ?: "文件不是UTF-8文本，请导出查看"}" }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tab.target, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { onExport(tab.target) }) { Text("导出", fontSize = 12.sp) }
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(vertical).horizontalScroll(horizontal)) {
            Text(body, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false, modifier = Modifier.padding(12.dp))
        }
    }
}
