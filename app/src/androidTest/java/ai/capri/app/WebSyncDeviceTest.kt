package ai.capri.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebSyncDeviceTest {
    @Test fun phoneDrawerActuallyRefreshesAndSelectionHonorsTabAndAttachmentAccessIsScoped() {
        val ins = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("am start -W -n ai.capri.app/.MainActivity")).use { it.readBytes() }
        var vm: CapriModel? = null
        repeat(60) {
            if (vm == null) {
                ins.runOnMainSync {
                    val activity = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED)
                        .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filterIsInstance<MainActivity>().firstOrNull()
                    if (activity != null) vm = ViewModelProvider(activity)[CapriModel::class.java]
                }
                if (vm == null) Thread.sleep(100)
            }
        }
        assertNotNull(vm)
        val model = vm!!; val original = model.selected; val originalTab = model.activeTab
        val title = "Web同步验收-" + UUID.randomUUID().toString().take(8)
        val target = Session(title = title)
        val other = Session(title = "隔离附件验收")
        fun call(method: String, chat: String) = DebugWebBridge.call(model, method, Bundle().apply {
            putString("chat_id", chat); putString("epoch", DebugWebBridge.epoch)
        })
        fun flatten(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList()
            else listOf(node) + (0 until node.childCount).flatMap { flatten(node.getChild(it)) }
        fun onScreen(text: String) = flatten(ins.uiAutomation.rootInActiveWindow).any { it.text?.toString() == text }
        try {
            ins.runOnMainSync {
                model.activeTab = 0; model.sessions.add(0, other); model.sessions.add(0, target); model.persist()
                assertTrue(call("web_select", target.id).getBoolean("phone_synced"))
                assertEquals(target.id, model.selected)
                model.activeTab = 2
                assertFalse(call("web_select", other.id).getBoolean("phone_synced"))
                assertEquals(target.id, model.selected)
                model.activeTab = 0; model.select(original)
            }
            val bytes = ByteArrayOutputStream().use { output ->
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xffa5ff75.toInt()); it.compress(Bitmap.CompressFormat.PNG, 100, output); it.recycle() }
                output.toByteArray()
            }
            val file = model.store.workspace.file(target.id, "fixture.png").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            val metadata = Attachments.inspect(model.store.workspace, target.id, "fixture.png", "网页附件验收")
            var ticket: DebugWebAttachments.Ticket? = null
            ins.runOnMainSync {
                target.messages.add(Entry("attachment", metadata.toString())); model.persist()
                val args = Bundle().apply { putString("chat_id", target.id); putString("epoch", DebugWebBridge.epoch); putInt("entry", 0) }
                ticket = DebugWebAttachments.prepare(model, args)
                assertTrue(runCatching { DebugWebAttachments.prepare(model, Bundle(args).apply { putString("chat_id", other.id) }) }.isFailure)
                assertTrue(runCatching { DebugWebAttachments.prepare(model, Bundle(args).apply { putString("epoch", "old") }) }.isFailure)
                target.messages.add(Entry("attachment", JSONObject(metadata.toString()).put("path", "../../config.xml").toString()))
                assertTrue(runCatching { DebugWebAttachments.prepare(model, Bundle(args).apply { putInt("entry", 1) }) }.isFailure)
            }
            val chunk = ticket!!.read()
            assertEquals("image/png", chunk.getString("mime"))
            assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(chunk.getString("data")))
            file.writeText("changed")
            assertTrue(runCatching { ticket!!.read() }.isFailure)
            ins.waitForIdleSync()
            val menu = flatten(ins.uiAutomation.rootInActiveWindow).firstOrNull { it.contentDescription?.toString() == "会话列表" }
            assertNotNull("Phone menu is not accessible", menu)
            var clickable = menu
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            assertTrue(clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            var appeared = false
            repeat(40) { if (!appeared) { appeared = onScreen(title); if (!appeared) Thread.sleep(100) } }
            assertTrue("New chat missing from actual phone drawer", appeared)
            ins.runOnMainSync { call("web_delete", target.id) }
            var removed = false
            repeat(150) {
                if (!removed) {
                    var done = false
                    ins.runOnMainSync { done = target.id !in model.deletingChats && model.sessions.none { it.id == target.id } }
                    removed = done && !onScreen(title)
                    if (!removed) Thread.sleep(100)
                }
            }
            assertTrue("Deleted chat still visible in phone drawer", removed)
        } finally {
            ins.runOnMainSync { model.sessions.removeAll { it.id == target.id || it.id == other.id }; model.activeTab = originalTab; model.select(original); model.persist() }
            SessionDeletion(ins.targetContext.filesDir).cleanup(target.id); SessionDeletion(ins.targetContext.filesDir).cleanup(other.id)
        }
    }
}
