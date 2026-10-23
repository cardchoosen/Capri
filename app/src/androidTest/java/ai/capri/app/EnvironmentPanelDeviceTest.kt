package ai.capri.app

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnvironmentPanelDeviceTest {
    @Test fun compactStateAndRepairConfirmationExplainScopeWithoutChangingUserData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -f 0x10008000 -n ai.capri.app/.MainActivity")).use { it.readBytes() }
        var activity: MainActivity? = null
        repeat(60) {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() }
            }
            if (activity != null) return@repeat
            Thread.sleep(100)
        }
        val screen = requireNotNull(activity)
        lateinit var vm: CapriModel
        var assigned = false
        var oldReport: EnvironmentReport? = null; var oldChat: String? = null
        fun nodes(text: String): List<AccessibilityNodeInfo> {
            instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
            if (root.packageName?.toString() != "ai.capri.app") return emptyList()
            val found = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                if (node.text?.toString()?.contains(text) == true) found += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            visit(root)
            return found
        }
        fun waitText(text: String): AccessibilityNodeInfo {
            repeat(50) { nodes(text).firstOrNull()?.let { return it }; Thread.sleep(100) }
            error("Missing UI text: $text")
        }
        fun click(text: String) {
            var node: AccessibilityNodeInfo? = waitText(text)
            while (node != null && !node.isClickable) node = node.parent
            assertTrue("Click failed: $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        try {
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(screen)[CapriModel::class.java]
                assigned = true
                oldReport = vm.environmentReport; oldChat = vm.environmentReportChat
                vm.runtimeBusy = true // Prevent automatic IO; this is a presentation-only fixture.
                vm.environmentReportChat = vm.selected
                vm.environmentReport = EnvironmentReport(listOf(
                    EnvironmentComponent("base", "基础 Python", "Python 3.12.15", "校验通过", 12345, "", true),
                    EnvironmentComponent("tools", "开发工具", "Node / GCC", "检测到差异", 1024, "usr/bin/node", false),
                    EnvironmentComponent("science", "Python 预编译库", "NumPy / Pillow", "校验通过", 2048, "", true)),
                    emptyList(), 100, 200, 300, 0, true)
                screen.setContent { vm.updates.collectAsState().value; MaterialTheme { Column(Modifier.fillMaxSize().padding(top = 48.dp).verticalScroll(rememberScrollState())) { EnvironmentPanel(vm) } } }
            }
            waitText("Node / GCC")
            instrumentation.runOnMainSync { vm.runtimeBusy = false; vm.refresh() }
            waitText("刷新状态"); waitText("检查环境"); waitText("当前会话")
            click("修复")
            waitText("重新部署基础 Python")
            waitText("手工修改会被覆盖")
            waitText("保留聊天")
            click("取消")
            Thread.sleep(200)
            assertTrue(nodes("确认重新部署").isEmpty())
        } finally {
            instrumentation.runOnMainSync {
                if (assigned) { vm.runtimeBusy = false; vm.environmentReport = oldReport; vm.environmentReportChat = oldChat }
                screen.finish()
            }
        }
    }
}
