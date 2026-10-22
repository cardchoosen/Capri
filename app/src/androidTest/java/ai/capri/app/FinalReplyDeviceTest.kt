package ai.capri.app

import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import android.content.Intent
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinalReplyDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun texts(): String {
        fun collect(node: AccessibilityNodeInfo): String {
            val own = node.text?.toString().orEmpty()
            return own + (0 until node.childCount).joinToString("") { index -> node.getChild(index)?.let(::collect).orEmpty() }
        }
        repeat(20) {
            val text = instrumentation.uiAutomation.rootInActiveWindow?.let(::collect).orEmpty()
            if (text.isNotEmpty()) return text
            Thread.sleep(50)
        }
        return ""
    }
    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun activity(excluded: MainActivity? = null): MainActivity {
        repeat(100) {
            var found: MainActivity? = null
            instrumentation.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { it !== excluded }
            }
            if (found != null) return found!!
            Thread.sleep(100)
        }
        error("Capri Activity did not resume")
    }
    @Test fun onlyFinalTextRevealsAndStoredHistoryLoadsComplete() {
        instrumentation.runOnMainSync {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        var foreground = activity()
        var previous = ""
        var chat = ""
        val original = "### 渐进自检\n\n" + "摩羯逐步显示正文。".repeat(75) +
            "\n\n```python\nprint('😀')\n```\n\n| 项目 | 状态 |\n| --- | --- |\n| 输出 | 正常 |\n\nREVEAL_END"
        try {
            instrumentation.runOnMainSync {
                val vm = ViewModelProvider(foreground)[CapriModel::class.java]
                previous = vm.selected
                vm.create(); chat = vm.selected
                vm.current().messages.add(Entry("assistant", "过程说明立即显示 PROCESS_OK"))
                vm.current().messages.add(Entry("tool", "terminal\n{\"action\":\"read\",\"status\":\"ok\"}"))
                vm.current().messages.add(Entry("assistant", original, FinalReplyReveal()))
                vm.persist()
            }
            Thread.sleep(350)
            val first = texts()
            capture("reveal-start.png")
            assertTrue(first, first.contains("PROCESS_OK"))
            assertTrue(first, first.contains("读取终端输出"))
            assertFalse(first, first.contains("REVEAL_END"))
            Thread.sleep(900)
            val second = texts()
            capture("reveal-middle.png")
            assertTrue("Text should grow: first=${first.length}, second=${second.length}", second.length > first.length)
            assertFalse(second, second.contains("REVEAL_END"))
            val context = instrumentation.targetContext
            val saved = Store(context).load().first { it.id == chat }
            assertEquals(original, saved.messages.last().text)
            assertNull(saved.messages.last().reveal)
            Thread.sleep(7300)
            assertTrue(texts(), texts().contains("REVEAL_END"))
            capture("reveal-complete.png")
            val previousActivity = foreground
            instrumentation.runOnMainSync { foreground.recreate() }
            foreground = activity(previousActivity)
            Thread.sleep(400)
            assertTrue("Activity recreation must not replay", texts().contains("REVEAL_END"))
        } finally {
            instrumentation.runOnMainSync {
                val vm = ViewModelProvider(foreground)[CapriModel::class.java]
                vm.sessions.removeAll { it.id == chat }
                vm.selected = previous.takeIf { id -> vm.sessions.any { it.id == id } } ?: vm.sessions.first().id
                vm.persist()
            }
            instrumentation.runOnMainSync { foreground.finish() }
        }
    }
}
