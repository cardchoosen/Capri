package ai.capri.app

import ai.capri.app.browser.*
import ai.capri.app.preview.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.io.File

class BrowserDeviceTest {
    @Test fun visibleBrowserSeparatesProfilesReadsBoundedDomAndRestoresTabsWithoutCrossChatAccess() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        val activity = withTimeout(10_000) {
            var found: MainActivity? = null
            while (found == null) {
                found = withContext(Dispatchers.Main) { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
                if (found == null) delay(100)
            }
            found
        }
        val directory = File(context.cacheDir, "browser-acceptance").apply { mkdirs() }
        val workspace = Workspace(directory)
        val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val clients = Executors.newCachedThreadPool()
        val listener = Thread {
            while (!server.isClosed) try {
                val socket = server.accept()
                clients.submit { socket.use { peer ->
                    val input = peer.getInputStream().bufferedReader()
                    val path = input.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                    while (!input.readLine().isNullOrEmpty()) { }
                    if (path.startsWith("/slow")) Thread.sleep(1500)
                    val set = when { path.startsWith("/set-a") -> "document.cookie='capri=a; path=/';localStorage.setItem('capri','a');"; path.startsWith("/set-b") -> "document.cookie='capri=b; path=/';localStorage.setItem('capri','b');"; else -> "" }
                    val body = """<!doctype html><html><head><title>Browser fixture</title></head><body><h1>Profile proof</h1><p id="proof"></p><label>Search<input name="q"></label><button>Submit</button><input type="password" value="fixture-password-never-return"><div hidden>fixture-hidden-never-return</div><script>$set document.getElementById('proof').textContent='cookie:'+document.cookie+' storage:'+localStorage.getItem('capri');</script></body></html>""".toByteArray()
                    val status = if (path.startsWith("/missing")) "404 Not Found" else "200 OK"
                    peer.getOutputStream().apply { write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
                } }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        var selected by mutableStateOf("browser-acceptance-a")
        val previews = PreviewTabs()
        val position = ChatScrollState(60).apply { followLatest = false }
        val entries = (0 until 60).map { Entry("assistant", "Reading fixture $it\n".repeat(8)) }
        val draft = mutableStateOf("draft-sentinel")
        fun controller() = BrowserController(context, workspace) { selected }.apply {
            pagesChanged = { chat, list -> previews.syncBrowser(chat, list.map { it.id to it.title }) }
            displayedPage = { previews.current(selected)?.takeIf { it.kind == PreviewKind.WEB }?.target }
        }
        var browser = controller()
        suspend fun mount() = withContext(Dispatchers.Main) {
            val current = browser
            activity.setContentView(ComposeView(activity).apply { setContent { MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF080B09), surface = Color(0xFF111612), primary = Color(0xFFA5FF75))) {
                WorkspaceSurface(previews, current, workspace, selected, active = true, modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    ConversationTimeline(entries, false, position, Modifier.weight(1f))
                    TextField(draft.value, { draft.value = it })
                }
            } } })
        }
        suspend fun call(action: String, value: String? = null): JSONObject {
            val args = JSONObject().put("action", action)
            value?.let { args.put(if (action == "open") "url" else "page_id", it) }
            val before = withContext(Dispatchers.Main) { previews.selected(selected) }
            val result = JSONObject(browser.execute(selected, args))
            withContext(Dispatchers.Main) { assertEquals("Agent must not switch preview", before, previews.selected(selected)) }
            return result
        }
        fun node(description: String): AccessibilityNodeInfo? {
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null) return null
                if (n.contentDescription?.toString() == description) return n
                for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                return null
            }
            return find(instrumentation.uiAutomation.rootInActiveWindow)
        }
        suspend fun click(description: String) {
            withTimeout(5000) { while (node(description) == null) delay(50) }
            assertTrue(description, node(description)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            delay(150)
        }
        fun text(result: JSONObject) = result.getJSONObject("observation").getString("text")
        val base = "http://127.0.0.1:${server.localPort}"
        try {
            mount()
            val status = call("status"); assertTrue(status.toString(), status.getBoolean("multi_profile"))
            delay(200)
            assertNull(node("会话子标签"))
            withContext(Dispatchers.Main) { position.list.scrollToItem(8, 23) }
            val anchor = withContext(Dispatchers.Main) { position.list.firstVisibleItemIndex to position.list.firstVisibleItemScrollOffset }
            val a = call("open", "$base/set-a"); assertEquals(a.toString(), "ok", a.getString("status"))
            assertTrue(text(a), text(a).contains("cookie:capri=a storage:a"))
            assertFalse(a.toString().contains("fixture-password-never-return")); assertFalse(a.toString().contains("fixture-hidden-never-return"))
            assertTrue(a.getJSONObject("observation").getJSONArray("elements").length() >= 3)
            val aid = a.getString("page_id")
            withContext(Dispatchers.Main) {
                assertEquals(anchor, position.list.firstVisibleItemIndex to position.list.firstVisibleItemScrollOffset)
                assertFalse(browser.view(aid)!!.hasFocus())
            }
            assertNotNull(node("会话子标签"))
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "preview-workspace-acceptance.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            click("预览：Browser fixture")
            withContext(Dispatchers.Main) { assertEquals(PreviewTabs.web(aid), previews.selected(selected)) }
            click("预览：对话")
            withContext(Dispatchers.Main) {
                assertEquals(anchor, position.list.firstVisibleItemIndex to position.list.firstVisibleItemScrollOffset)
                assertEquals("draft-sentinel", draft.value)
            }
            workspace.file(selected, "main.cpp").writeText("int main() { return 0; }")
            withContext(Dispatchers.Main) { previews.openFile(selected, "main.cpp") }
            click("关闭预览：main.cpp")
            assertTrue(workspace.file(selected, "main.cpp").isFile)
            withContext(Dispatchers.Main) { previews.openFile(selected, "main.cpp") }
            withContext(Dispatchers.Main) { selected = "browser-acceptance-b"; browser.selectionChanged(selected) }
            val b0 = call("open", "$base/read"); assertEquals(b0.toString(), "ok", b0.getString("status"))
            assertFalse(text(b0), text(b0).contains("capri=a")); assertFalse(text(b0), text(b0).contains("storage:a"))
            assertEquals("invalid_argument", call("observe", aid).getString("status"))
            val b = call("open", "$base/set-b"); assertTrue(b.toString(), text(b).contains("cookie:capri=b storage:b"))
            withContext(Dispatchers.Main) { selected = "browser-acceptance-a"; browser.selectionChanged(selected) }
            // At most two live views: A is recreated, its profile must still retain login/storage.
            val again = call("show", aid); assertEquals(again.toString(), "ok", again.getString("status"))
            assertTrue(text(again), text(again).contains("cookie:capri=a storage:a"))
            val read = call("open", "$base/read?token=fixture-not-persisted")
            assertTrue(read.toString(), text(read).contains("cookie:capri=a storage:a"))
            val readId = read.getString("page_id")
            val opening = async { call("open", "$base/slow") }
            val slowId = withTimeout(5000) {
                var id: String? = null
                while (id == null) {
                    id = withContext(Dispatchers.Main) { browser.tabList(selected).firstOrNull { it.url.endsWith("/slow") }?.id }
                    if (id == null) delay(25)
                }
                id
            }
            withContext(Dispatchers.Main) { browser.closePreview(selected, slowId) }
            assertNotEquals("ok", opening.await().optString("status"))
            withContext(Dispatchers.Main) { assertFalse(browser.tabList(selected).any { it.id == slowId }) }
            assertFalse(File(workspace.root(selected), "browser/pages.json").readText().contains("fixture-not-persisted"))
            withContext(Dispatchers.Main) { browser.dispose(); browser = controller() }
            mount()
            assertEquals(2, call("tabs").getJSONArray("tabs").length())
            val restored = call("show", readId); assertEquals(restored.toString(), "ok", restored.getString("status"))
            assertTrue(restored.getBoolean("recreated")); assertTrue(text(restored).contains("capri=a"))
            val missing = call("open", "$base/missing"); assertEquals(missing.toString(), "load_failed", missing.getString("status"))
            val closed = call("close", readId); assertTrue(closed.toString(), closed.getBoolean("closed"))
            assertEquals("invalid_argument", call("observe", readId).getString("status"))
            for (tab in withContext(Dispatchers.Main) { browser.tabList(selected) }) call("close", tab.id)
            click("关闭预览：main.cpp")
            withContext(Dispatchers.Main) { assertEquals(PreviewTabs.CHAT, previews.selected(selected)); assertTrue(previews.tabs(selected).isEmpty()) }
            delay(100); assertNull(node("会话子标签"))
            assertTrue(workspace.file(selected, "main.cpp").isFile)
        } finally {
            withContext(Dispatchers.Main) { browser.dispose(); activity.recreate() }
            server.close(); clients.shutdownNow(); listener.join(1000)
            RuntimeFiles.delete(directory)
        }
    }
}
