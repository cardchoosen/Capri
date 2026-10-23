package ai.capri.app

import ai.capri.app.browser.*
import android.content.Intent
import androidx.compose.material3.MaterialTheme
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
                    val set = when { path.startsWith("/set-a") -> "document.cookie='capri=a; path=/';localStorage.setItem('capri','a');"; path.startsWith("/set-b") -> "document.cookie='capri=b; path=/';localStorage.setItem('capri','b');"; else -> "" }
                    val body = """<!doctype html><html><head><title>Browser fixture</title></head><body><h1>Profile proof</h1><p id="proof"></p><label>Search<input name="q"></label><button>Submit</button><input type="password" value="fixture-password-never-return"><div hidden>fixture-hidden-never-return</div><script>$set document.getElementById('proof').textContent='cookie:'+document.cookie+' storage:'+localStorage.getItem('capri');</script></body></html>""".toByteArray()
                    val status = if (path.startsWith("/missing")) "404 Not Found" else "200 OK"
                    peer.getOutputStream().apply { write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
                } }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        var selected = "browser-acceptance-a"
        var browser = BrowserController(context, workspace) { selected }
        suspend fun mount() = withContext(Dispatchers.Main) {
            val current = browser
            activity.setContentView(ComposeView(activity).apply { setContent { MaterialTheme { BrowserPanel(current) } } })
        }
        suspend fun call(action: String, value: String? = null): JSONObject {
            val args = JSONObject().put("action", action)
            value?.let { args.put(if (action == "open") "url" else "page_id", it) }
            return JSONObject(browser.execute(selected, args))
        }
        fun text(result: JSONObject) = result.getJSONObject("observation").getString("text")
        val base = "http://127.0.0.1:${server.localPort}"
        try {
            mount()
            val status = call("status"); assertTrue(status.toString(), status.getBoolean("multi_profile"))
            val a = call("open", "$base/set-a"); assertEquals(a.toString(), "ok", a.getString("status"))
            assertTrue(text(a), text(a).contains("cookie:capri=a storage:a"))
            assertFalse(a.toString().contains("fixture-password-never-return")); assertFalse(a.toString().contains("fixture-hidden-never-return"))
            assertTrue(a.getJSONObject("observation").getJSONArray("elements").length() >= 3)
            val aid = a.getString("page_id")
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
            assertFalse(File(workspace.root(selected), "browser/pages.json").readText().contains("fixture-not-persisted"))
            withContext(Dispatchers.Main) { browser.dispose(); browser = BrowserController(context, workspace) { selected } }
            mount()
            assertEquals(2, call("tabs").getJSONArray("tabs").length())
            val restored = call("show", readId); assertEquals(restored.toString(), "ok", restored.getString("status"))
            assertTrue(restored.getBoolean("recreated")); assertTrue(text(restored).contains("capri=a"))
            val missing = call("open", "$base/missing"); assertEquals(missing.toString(), "load_failed", missing.getString("status"))
            val closed = call("close", readId); assertTrue(closed.toString(), closed.getBoolean("closed"))
            assertEquals("invalid_argument", call("observe", readId).getString("status"))
        } finally {
            withContext(Dispatchers.Main) { browser.dispose(); activity.recreate() }
            server.close(); clients.shutdownNow(); listener.join(1000)
            RuntimeFiles.delete(directory)
        }
    }
}
