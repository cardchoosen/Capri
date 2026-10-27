package ai.capri.app

import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TimelineDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun activity(): MainActivity {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ai.capri.app/.MainActivity")).use { it.readBytes() }
        repeat(60) {
            var found: MainActivity? = null
            instrumentation.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { !it.isChangingConfigurations && !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() }
            }
            if (found != null) return found!!
            Thread.sleep(100)
        }
        error("Activity unavailable")
    }
    @Test fun readingAnchorStaysWhileFinalGrowsAndHistoryHasTwoFoldingLevels() = runBlocking<Unit> {
        val activity = activity()
        val entries = mutableStateListOf(Entry("user", "timeline fixture"), Entry("assistant", "process text"),
            Entry("tool", "list_files\nfixture.txt"), Entry("assistant", "next step"), Entry("tool", "python_run\nexit=0\nfixture"))
        val position = ChatScrollState(entries.size)
        try {
            instrumentation.runOnMainSync {
                activity.setContent { MaterialTheme {
                    Column(Modifier.fillMaxSize().padding(top = 48.dp, bottom = 64.dp)) {
                        ConversationTimeline(entries.toList(), true, position, Modifier.weight(1f))
                    }
                } }
            }
            Thread.sleep(300)
            instrumentation.runOnMainSync { assertEquals(3, position.list.layoutInfo.totalItemsCount) }
            val text = (1..220).joinToString("\n\n") { "READ_ANCHOR $it：这里是供阅读的长段落，后续内容显示时，已阅读位置应该保持稳定。".repeat(3) }
            val freshReveal = FinalReplyReveal()
            instrumentation.runOnMainSync { entries.add(Entry("assistant", text, freshReveal)) }
            Thread.sleep(700)
            withContext(Dispatchers.Main) {
                assertEquals(4, position.list.layoutInfo.totalItemsCount) // user, group, final, sentinel
                position.followLatest = false
                position.list.scrollToItem(2, 80)
            }
            Thread.sleep(100)
            var anchor = 0 to 0
            var initialTextLength = 0
            var initialY = 0
            fun richText(view: View): TextView? {
                if (view is TextView && view.text.toString().contains("READ_ANCHOR")) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) richText(view.getChildAt(i))?.let { return it }
                return null
            }
            instrumentation.runOnMainSync {
                anchor = position.list.firstVisibleItemIndex to position.list.firstVisibleItemScrollOffset
                val view = requireNotNull(richText(activity.window.decorView))
                initialTextLength = view.text.length
                initialY = IntArray(2).also(view::getLocationOnScreen)[1]
            }
            Thread.sleep(900)
            instrumentation.runOnMainSync {
                assertEquals("Reading anchor moved during reveal", anchor, position.list.firstVisibleItemIndex to position.list.firstVisibleItemScrollOffset)
                val view = requireNotNull(richText(activity.window.decorView))
                assertTrue("Reveal must still advance", view.text.length > initialTextLength)
                assertEquals("Visible prose shifted", initialY, IntArray(2).also(view::getLocationOnScreen)[1])
                freshReveal.finish()
            }
            Thread.sleep(150)
            withContext(Dispatchers.Main) { position.list.scrollToItem(1) }
            Thread.sleep(200)
            fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString() == label) return node
                for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
                return null
            }
            val group = find(instrumentation.uiAutomation.rootInActiveWindow, "工具记录 · 2 条")
            assertNotNull("History summary should be visible", group)
            // The label may be a child of the merged clickable semantics node.
            var button = group
            while (button != null && !button.isClickable) button = button.parent
            assertNotNull(button)
            assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(200)
            instrumentation.runOnMainSync { assertEquals(true, position.expandedGroups[1]) }
            assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow, "查看工作区"))
            assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow, "运行 Python"))
            val tool = find(instrumentation.uiAutomation.rootInActiveWindow, "查看工作区")!!
            var toolButton: AccessibilityNodeInfo? = tool
            while (toolButton != null && !toolButton.isClickable) toolButton = toolButton.parent
            assertTrue(toolButton!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(200)
            assertNotNull("Individual output still expands", find(instrumentation.uiAutomation.rootInActiveWindow, "fixture.txt"))
            withContext(Dispatchers.Main) {
                position.followLatest = true
                position.list.scrollToItem(3)
            }
            Thread.sleep(200)
            instrumentation.runOnMainSync { assertFalse(position.list.canScrollForward) }
            val down = android.os.SystemClock.uptimeMillis()
            var x = 0f; var y = 0f; var distance = 0f
            instrumentation.runOnMainSync {
                x = activity.window.decorView.width * .5f
                y = activity.window.decorView.height * .3f
                distance = activity.window.decorView.height * .3f
            }
            fun touch(action: Int, at: Float) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, at, 0)
                instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
            }
            touch(android.view.MotionEvent.ACTION_DOWN, y)
            for (step in 1..12) { Thread.sleep(12); touch(android.view.MotionEvent.ACTION_MOVE, y + distance * step / 12) }
            touch(android.view.MotionEvent.ACTION_UP, y + distance)
            Thread.sleep(650)
            instrumentation.runOnMainSync {
                assertFalse("A real reading gesture must stop following", position.followLatest)
                assertTrue("Reader should be above the bottom", position.list.canScrollForward)
            }
            // Same state survives removing/recreating tab content; a new state opens at the bottom.
            val savedAnchor = withContext(Dispatchers.Main) { position.list.firstVisibleItemIndex to position.list.firstVisibleItemScrollOffset }
            instrumentation.runOnMainSync { activity.setContent { androidx.compose.material3.Text("other tab") } }
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme { ConversationTimeline(entries.toList(), false, position, Modifier.fillMaxSize()) } } }
            Thread.sleep(250)
            instrumentation.runOnMainSync {
                assertEquals(savedAnchor, position.list.firstVisibleItemIndex to position.list.firstVisibleItemScrollOffset)
            }
            val restarted = ChatScrollState(entries.size)
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme { ConversationTimeline(entries.toList(), false, restarted, Modifier.fillMaxSize()) } } }
            Thread.sleep(250)
            instrumentation.runOnMainSync {
                assertFalse("Fresh history should open at the bottom", restarted.list.canScrollForward)
                assertEquals(4, restarted.list.layoutInfo.totalItemsCount)
            }
        } finally {
            instrumentation.runOnMainSync { activity.recreate() }
        }
    }
    @Test fun finalMarkerSurvivesReloadWithoutReplayingReveal() {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "timeline-store-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = directory }
        try {
            Store(isolated).save(listOf(Session(messages = mutableListOf(Entry("assistant", "final", FinalReplyReveal())))))
            val entry = Store(isolated).load().first { it.kind == SessionKind.TASK }.messages.single()
            assertTrue(entry.finalReply)
            assertNull(entry.reveal)
        } finally { directory.deleteRecursively() }
    }
}
