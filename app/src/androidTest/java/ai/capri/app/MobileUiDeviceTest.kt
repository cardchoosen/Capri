package ai.capri.app

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Navigation-only smoke; no messages, configuration changes, model calls or public contributions. */
class MobileUiDeviceTest {
    @Test fun settingsUsageAndWorkbenchPreserveTheSelectedConversation() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm: CapriModel; var previousTab = 0; var chat = ""
        fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if(node == null)return null
            if(predicate(node))return node
            for(i in 0 until node.childCount) find(node.getChild(i),predicate)?.let { return it }
            return null
        }
        fun root(): AccessibilityNodeInfo? { ins.uiAutomation.clearCache(); return ins.uiAutomation.rootInActiveWindow }
        fun node(text: String, description: Boolean = false): AccessibilityNodeInfo {
            repeat(80) {
                find(root()) { if(description) it.contentDescription?.toString() == text else it.text?.toString() == text }?.let { return it }
                Thread.sleep(100)
            }
            error("Missing UI entry: $text")
        }
        fun tap(text: String, description: Boolean = false) {
            var current = node(text,description)
            var scrolls = 0
            while(!current.isVisibleToUser && scrolls++ < 8) {
                find(root()) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(150);current = node(text,description)
            }
            while(!current.isClickable && current.parent != null) current = current.parent
            assertTrue("Entry must be clickable: $text",current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun capture(name: String) {
            ins.waitForIdleSync()
            Thread.sleep(400) // Wait for the platform dialog enter/exit animation before visual QA.
            ins.uiAutomation.takeScreenshot().let { bitmap -> File(ins.targetContext.cacheDir,"mobile-ui-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        }
        try {
            scenario.onActivity { activity ->
                vm = AgentRuntime.get(activity.application); previousTab = vm.activeTab; chat = vm.selected
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                vm.activeTab = 3
            }
            ins.waitForIdleSync()
            assertNull("Stored contributions must not open a modal at app startup",find(root()) { it.text?.toString() == "是否公开贡献这个 Skill？" })
            node("模型与账号"); node("能力与扩展"); node("模型配置"); node("用量")
            capture("settings")
            tap("模型配置"); node("添加 API"); tap("返回",true)
            tap("Skills"); node("公共 Skill 仓库"); capture("skills"); tap("返回",true)
            scenario.onActivity { vm.activeTab = 2 }
            node("账号余额"); node("最近本机记录");capture("usage")
            scenario.onActivity { vm.activeTab = 0 }
            tap("工作台",true);node("浏览器");node("终端");capture("workbench");tap("返回",true)
            scenario.onActivity { assertEquals(chat,vm.selected) }
        } finally { scenario.onActivity { vm.activeTab = previousTab }; scenario.close() }
    }
}
