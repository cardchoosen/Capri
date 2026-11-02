package ai.capri.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Read-only navigation and unsaved draft inspection; never saves, toggles, authorizes or publishes. */
class SettingsPagesDeviceTest {
    @Test fun configurationPagesExposeGroupedFormsAndPreserveUserData() {
        val ins=InstrumentationRegistry.getInstrumentation();val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm:CapriModel;var previous=0;var chat=""
        fun find(node:AccessibilityNodeInfo?,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            if(node==null)return null;if(predicate(node))return node
            for(i in 0 until node.childCount)find(node.getChild(i),predicate)?.let{return it};return null
        }
        fun root():AccessibilityNodeInfo? {ins.uiAutomation.clearCache();return ins.uiAutomation.rootInActiveWindow}
        fun locate(text:String,description:Boolean=false):AccessibilityNodeInfo?=find(root()){if(description)it.contentDescription?.toString()==text else it.text?.toString()==text}
        fun node(text:String,description:Boolean=false):AccessibilityNodeInfo {
            repeat(60){locate(text,description)?.let{return it};Thread.sleep(80)};error("Missing configuration entry: $text")
        }
        fun tap(text:String,description:Boolean=false) {
            var current=locate(text,description)
            if(current==null||!current!!.isVisibleToUser) {
                for(direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    repeat(14) { if(current==null||!current!!.isVisibleToUser){find(root()){it.isScrollable}?.performAction(direction);Thread.sleep(100);current=locate(text,description)} }
                    if(current?.isVisibleToUser==true)break
                }
            }
            var target=current?:node(text,description)
            while(!target.isClickable&&target.parent!=null)target=target.parent
            assertTrue(text,target.performAction(AccessibilityNodeInfo.ACTION_CLICK));ins.waitForIdleSync()
        }
        fun back(){tap("返回",true)}
        fun capture(name:String){ins.waitForIdleSync();Thread.sleep(400);ins.uiAutomation.takeScreenshot().let{bitmap->File(ins.targetContext.cacheDir,"settings-polish-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}}
        try {
            scenario.onActivity{activity->vm=AgentRuntime.get(activity.application);previous=vm.activeTab;chat=vm.selected;vm.activeTab=3;activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            tap("模型配置");node("全局 · 模型账号");capture("models");tap("添加 API");node("服务与身份");node("模型列表");capture("model-editor")
            tap("API Key");Thread.sleep(400)
            assertTrue("Save action must remain visible with the keyboard",node("保存并获取模型").isVisibleToUser)
            scenario.onActivity{activity->(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(activity.currentFocus?.windowToken,0)}
            back();back()
            tap("Git 账号");tap("添加账号");node("账号信息");capture("git-editor");back();back()
            tap("规则管理");tap("添加规则");node("规则内容");node("保存规则");capture("rules-editor");back();back()
            tap("MCP");tap("添加远端");node("基本信息");node("身份认证");node("保存");capture("mcp-editor");back();back()
            tap("Skills");node("公共 Skill 仓库");tap("公共 Skill 仓库");node("获取与更新");back();back()
            tap("Tools");node("搜索工具");back()
            tap("记忆与资料");node("上下文概览");back()
            tap("增强能力");node("联网服务");back()
            tap("Android 权限");node("搜索设备能力");back()
            tap("执行环境");node("环境状态");back()
            tap("存储管理");node("可用空间");back()
            tap("Root");node("授权状态");back()
            tap("电脑网页连接");node("连接步骤");back()
            tap("关于 Capri");node("高级运行说明");back()
            scenario.onActivity{assertEquals(chat,vm.selected)}
        }finally{scenario.onActivity{vm.activeTab=previous};scenario.close()}
    }
}
