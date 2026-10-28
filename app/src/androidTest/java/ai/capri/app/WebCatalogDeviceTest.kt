package ai.capri.app

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WebCatalogDeviceTest {
    @Test fun directoryIsReadOnlyScopedAndDefaultChatIsFirst() {
        val ins = InstrumentationRegistry.getInstrumentation()
        ins.runOnMainSync { ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        var vm: CapriModel? = null
        repeat(60) {
            if (vm == null) ins.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.let { vm = ViewModelProvider(it)[CapriModel::class.java] } }
            if (vm == null) Thread.sleep(100)
        }
        val model = requireNotNull(vm); val original = model.selected
        val chat = Session(title = "Catalog fixture")
        val server = "catalog-${UUID.randomUUID()}"
        fun read(category: String, id: String = "", offset: Int = 0, epoch: String = DebugWebBridge.epoch): JSONObject {
            var ticket: DebugWebCatalog.Ticket? = null
            ins.runOnMainSync { ticket = DebugWebCatalog.prepare(model, Bundle().apply {
                putString("chat_id", chat.id); putString("category", category); putString("id", id); putInt("offset", offset); putString("epoch", epoch)
            }) }
            return ticket!!.read()
        }
        try {
            ins.runOnMainSync {
                model.sessions.add(0, chat); model.persist()
                val state = DebugWebBridge.call(model, "web_state", Bundle().apply { putString("chat_id", original) })
                assertEquals("PERSONAL", state.getJSONArray("sessions").getJSONObject(0).getString("kind"))
                assertEquals(original, model.selected)
            }
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?, match: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (match(node)) return node
                for (i in 0 until node.childCount) find(node.getChild(i), match)?.let { return it }
                return null
            }
            var menu: android.view.accessibility.AccessibilityNodeInfo? = null
            repeat(30) { if (menu == null) { ins.uiAutomation.clearCache(); menu = find(ins.uiAutomation.rootInActiveWindow) { it.contentDescription?.toString() == "会话列表" }; if (menu == null) Thread.sleep(50) } }
            while (menu != null && !menu!!.isClickable) menu = menu!!.parent
            assertTrue(requireNotNull(menu).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            var pinned: android.view.accessibility.AccessibilityNodeInfo? = null
            var task: android.view.accessibility.AccessibilityNodeInfo? = null
            repeat(30) {
                ins.uiAutomation.clearCache()
                if (pinned == null) pinned = find(ins.uiAutomation.rootInActiveWindow) { it.text?.toString() == "默认会话 · 一直在这里" }
                if (task == null) task = find(ins.uiAutomation.rootInActiveWindow) { it.text?.toString() == chat.title }
                if (pinned == null || task == null) Thread.sleep(50)
            }
            val pinnedBounds = android.graphics.Rect(); val taskBounds = android.graphics.Rect()
            requireNotNull(pinned).getBoundsInScreen(pinnedBounds); requireNotNull(task).getBoundsInScreen(taskBounds)
            assertTrue(pinnedBounds.top < taskBounds.top)
            ins.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            model.extensions.store.installConfig(JSONObject().put("id", server).put("name", "Catalog fixture").put("type", "remote").put("url", "https://example.com/private-catalog-fixture").put("enabled", false))
            model.extensions.store.saveSecret(server, "catalog-fixture-secret-never-export")
            val tools = read("tools")
            assertTrue(tools.toString(), tools.getInt("total") >= 20)
            assertEquals(8, tools.getJSONArray("items").length())
            val details = read("tools", "read_file").getJSONObject("item")
            assertEquals("read_file", details.getString("id")); assertTrue(details.getJSONObject("parameters").has("properties"))
            assertFalse(read("tools", "unknown-item").has("item"))
            val config = read("mcp", server)
            assertFalse(config.toString().contains("catalog-fixture-secret-never-export"))
            assertFalse(config.toString().contains("private-catalog-fixture"))
            assertFalse(config.getJSONObject("item").getBoolean("enabled")); assertFalse(config.getJSONObject("item").getBoolean("active"))
            val skill = read("skills", "skill-creator").getJSONObject("item")
            assertTrue(skill.getString("content").contains("name: skill-creator")); assertTrue(skill.getBoolean("builtin"))
            assertEquals(original, model.selected)
            assertFalse(model.extensions.store.active(chat.id, server))
            assertFalse(model.store.workspace.sessionDirectory(chat.id).resolve("workspace/.capri/skills/skill-creator").exists())
            var denied = false
            ins.runOnMainSync { try { DebugWebCatalog.prepare(model, Bundle().apply { putString("chat_id", chat.id); putString("epoch", "stale") }) } catch (_: IllegalArgumentException) { denied = true } }
            assertTrue(denied)
        } finally {
            model.extensions.store.removeConfig(server)
            ins.runOnMainSync { model.sessions.removeAll { it.id == chat.id }; model.persist() }
        }
    }
}
