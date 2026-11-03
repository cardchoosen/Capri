package ai.capri.app

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Optional foreground UI smoke test; backend profile tests do not require UI automation. */
class ReaUiDeviceTest {
    @Test fun runPanelReflectsSharedReaSettingAndRestoresIt() {
        val ins=InstrumentationRegistry.getInstrumentation();val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm:CapriModel;var previousTab=0;var previous=false
        fun find(node:AccessibilityNodeInfo?,test:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            if(node==null)return null;if(test(node))return node
            for(i in 0 until node.childCount)find(node.getChild(i),test)?.let{return it};return null
        }
        fun root():AccessibilityNodeInfo? { ins.uiAutomation.clearCache();return ins.uiAutomation.rootInActiveWindow }
        try {
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);vm=AgentRuntime.get(it.application);previousTab=vm.activeTab;previous=vm.extensions.rea.enabled;vm.activeTab=2 }
            var button:AccessibilityNodeInfo?=null
            for(attempt in 0 until 50){
                val tree=root();button=find(tree){it.text?.toString()=="增强能力"}
                if(button!=null)break
                if(attempt>10&&attempt%5==0)find(tree){it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(60)
            }
            var clickable=requireNotNull(button){"Run enhancement entry is not visible; keep the phone unlocked."}
            while(!clickable.isClickable&&clickable.parent!=null)clickable=clickable.parent
            assertTrue(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            var rea:AccessibilityNodeInfo?=null
            for(attempt in 0 until 50){rea=find(root()){it.text?.toString()=="REA 逆向分析"};if(rea!=null)break;Thread.sleep(60)}
            assertNotNull(rea)
            assertEquals(previous,vm.extensions.rea.enabled)
            kotlinx.coroutines.runBlocking { vm.extensions.setReaEnabled(vm.selected,!previous) }
            ins.runOnMainSync { vm.refresh() }
            fun nearestSwitch(): AccessibilityNodeInfo? {
                val tree=root();val label=find(tree){it.text?.toString()=="REA 逆向分析"}?:return null
                val anchor=android.graphics.Rect();label.getBoundsInScreen(anchor)
                val candidates=mutableListOf<AccessibilityNodeInfo>()
                fun collect(node:AccessibilityNodeInfo?){if(node==null)return;if(node.isCheckable)candidates+=node;for(i in 0 until node.childCount)collect(node.getChild(i))}
                collect(tree)
                return candidates.minByOrNull { val box=android.graphics.Rect();it.getBoundsInScreen(box);kotlin.math.abs(box.centerY()-anchor.centerY()) }
            }
            var reflected=false
            for(attempt in 0 until 40){if(nearestSwitch()?.isChecked==!previous){reflected=true;break};Thread.sleep(50)}
            assertTrue("The open phone panel must reflect the shared setting update.",reflected)
            kotlinx.coroutines.runBlocking { vm.extensions.setReaEnabled(vm.selected,previous) }
            ins.runOnMainSync { vm.refresh() }
            ins.uiAutomation.takeScreenshot().let { bitmap -> File(ins.targetContext.cacheDir,"rea-enhancements-ui.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)} }
            find(root()){it.text?.toString()=="关闭"}?.let { var n=it;while(!n.isClickable&&n.parent!=null)n=n.parent;n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        } finally { kotlinx.coroutines.runBlocking { vm.extensions.setReaEnabled(vm.selected,previous) };scenario.onActivity{vm.refresh();vm.activeTab=previousTab};scenario.close() }
    }
}
