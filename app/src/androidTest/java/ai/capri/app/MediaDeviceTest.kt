package ai.capri.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun activity(): MainActivity {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ai.capri.app/.MainActivity")).use { it.readBytes() }
        repeat(60) {
            var found: MainActivity? = null
            instrumentation.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull { !it.isChangingConfigurations && !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() }
            }
            if (found != null) return found!!
            Thread.sleep(100)
        }
        error("Activity unavailable")
    }
    @Test fun runningToolCarouselStaysOneRowAndSpinnerFollowsActualCall() {
        val activity = activity()
        val entries = mutableStateListOf(Entry("user", "carousel fixture"))
        val current = mutableStateOf<ToolActivity?>(ToolActivity("one", "查看工作区"))
        val busy = mutableStateOf(true)
        val position = ChatScrollState(1)
        fun visible(): String {
            fun collect(node: android.view.accessibility.AccessibilityNodeInfo?): String {
                if (node == null) return ""
                return node.text?.toString().orEmpty() + "|" + node.contentDescription?.toString().orEmpty() +
                    (0 until node.childCount).joinToString("|") { collect(node.getChild(it)) }
            }
            instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            return if (root?.packageName?.toString() == "ai.capri.app") collect(root) else ""
        }
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                ConversationTimeline(entries.toList(), busy.value, position, Modifier.fillMaxSize().padding(top = 60.dp), current.value)
            } } }
            Thread.sleep(350)
            assertTrue("Expected carousel label", visible().contains("查看工作区"))
            assertTrue("Expected carousel label", visible().contains("工具调用中"))
            instrumentation.runOnMainSync {
                entries.add(Entry("tool", "list_files\nfixture.txt"))
                current.value = ToolActivity("two", "运行 Python")
            }
            Thread.sleep(650)
            var foundNew = false
            repeat(30) { if (!foundNew) { foundNew = visible().contains("运行 Python"); if (!foundNew) Thread.sleep(50) } }
            var focus = false
            instrumentation.runOnMainSync { focus = activity.hasWindowFocus() }
            assertTrue("New tool missing; oldLabel=${visible().contains("查看工作区")}, focused=$focus", foundNew)
            assertFalse("Unexpected carousel label", visible().contains("查看工作区"))
            instrumentation.runOnMainSync { assertEquals(3, position.list.layoutInfo.totalItemsCount) }
            instrumentation.runOnMainSync {
                entries.add(Entry("tool", "python_run\nexit=0\nfixture")); current.value = null
            }
            Thread.sleep(350)
            assertFalse("Unexpected carousel label", visible().contains("工具调用中"))
            instrumentation.runOnMainSync { entries.add(Entry("assistant", "fixture final", finalReply = true)); busy.value = false }
            Thread.sleep(650)
            assertTrue("Expected carousel label", visible().contains("工具记录 · 2 条"))
            assertFalse("Unexpected carousel label", visible().contains("fixture.txt"))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun filesAreValidatedAndGifActuallyChangesFramesInChat() {
        val activity = activity()
        val root = File(instrumentation.targetContext.cacheDir, "media-test-${UUID.randomUUID()}")
        val workspace = Workspace(root)
        val chat = "media-test"
        val gif = workspace.file(chat, "test.gif")
        var rendered: AnimatedImageDrawable? = null
        try {
            val arguments = InstrumentationRegistry.getArguments()
            val sourceChat = arguments.getString("media_source_chat")
            if (sourceChat == null) instrumentation.context.assets.open("test-animation.gif").use { input -> gif.outputStream().use { input.copyTo(it) } }
            else Store(instrumentation.targetContext).workspace.file(sourceChat, arguments.getString("media_source_path") ?: "bubble_sort.gif").copyTo(gif)
            val item = Attachments.inspect(workspace, chat, "/workspace/test.gif", "animated fixture")
            assertEquals("image/gif", item.getString("mime")); assertTrue(item.getInt("width") > 0)
            val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            workspace.file(chat, "static.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            assertEquals("image/png", Attachments.inspect(workspace, chat, "static.png", "").getString("mime"))
            workspace.file(chat, "invalid.gif").writeText("not an image")
            for (path in listOf("invalid.gif", "missing.gif", "../other-chat/key", "/opt/capri/ssh/id_ed25519")) {
                try { Attachments.inspect(workspace, chat, path, ""); fail("Accepted $path") } catch (_: IllegalArgumentException) { }
            }
            fun image(view: View): ImageView? {
                if (view is ImageView && view.contentDescription == "test.gif") return view
                if (view is ViewGroup) for (i in 0 until view.childCount) image(view.getChildAt(i))?.let { return it }
                return null
            }
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                Column(Modifier.fillMaxSize().padding(top = 80.dp)) { AttachmentView(item.toString(), workspace, chat) }
            } } }
            repeat(40) {
                instrumentation.runOnMainSync { rendered = image(activity.window.decorView)?.drawable as? AnimatedImageDrawable }
                if (rendered == null) Thread.sleep(50)
            }
            assertNotNull("GIF should decode as animation", rendered)
            instrumentation.runOnMainSync { assertTrue("Visible GIF should be running", rendered!!.isRunning) }
            val samples = mutableSetOf<Int>()
            repeat(12) {
                Thread.sleep(45)
                instrumentation.runOnMainSync {
                    val width = rendered!!.intrinsicWidth; val height = rendered!!.intrinsicHeight
                    val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    rendered!!.setBounds(0, 0, width, height); rendered!!.draw(Canvas(frame))
                    val pixels = IntArray(width * height)
                    frame.getPixels(pixels, 0, width, 0, 0, width, height)
                    samples += pixels.contentHashCode(); frame.recycle()
                }
            }
            assertTrue("Animated GIF must change pixels, not just show first frame", samples.size >= 2)
            instrumentation.runOnMainSync { activity.setContent { androidx.compose.material3.Text("other tab") } }
            Thread.sleep(150)
            instrumentation.runOnMainSync { assertFalse("Disposed animation should stop", rendered!!.isRunning) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            root.deleteRecursively()
        }
    }
}
