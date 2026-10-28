package ai.capri.app

import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SessionDeletionDeviceTest {
    @Test fun protectedDefaultBusyGuardAndWebDeletionShareOneStore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ai.capri.app/.MainActivity")).use { it.readBytes() }
        var vm: CapriModel? = null
        repeat(60) {
            if (vm == null) {
                instrumentation.runOnMainSync {
                    val activity = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED)
                        .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                        .filterIsInstance<MainActivity>().firstOrNull()
                    if (activity != null) vm = ViewModelProvider(activity)[CapriModel::class.java]
                }
                if (vm == null) Thread.sleep(100)
            }
        }
        assertNotNull(vm)
        val target = Session(title = "待删除专项测试")
        val neighbor = Session(title = "保留专项测试")
        val original = vm!!.selected
        val modelKeyHash = vm!!.store.config().key.hashCode()
        instrumentation.runOnMainSync {
            vm!!.sessions.add(target); vm!!.sessions.add(neighbor); vm!!.persist()
            assertTrue(runCatching { vm!!.deleteSession(vm!!.personalSession().id) }.isFailure)
            val busy = kotlinx.coroutines.Job(); vm!!.jobs[target.id] = busy
            assertTrue(runCatching { vm!!.deleteSession(target.id) }.isFailure)
            vm!!.jobs.remove(target.id); busy.cancel()
        }
        File(context.filesDir, "sessions/${target.id}/workspace/test.txt").apply { parentFile!!.mkdirs(); writeText("delete") }
        val keep = File(context.filesDir, "sessions/${neighbor.id}/workspace/test.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
        File(context.filesDir, "context-history/${target.id}/summary.txt").apply { parentFile!!.mkdirs(); writeText("delete") }
        File(context.filesDir, "web-retrieval/${target.id}/source.json").apply { parentFile!!.mkdirs(); writeText("delete") }
        val terminal = kotlinx.coroutines.runBlocking { JSONObject(vm!!.terminals.execute(target.id, JSONObject().put("action", "open").put("title", "删除测试终端"))) }
        assertEquals(terminal.optString("message"), "ok", terminal.getString("status"))
        assertTrue(terminal.getJSONObject("data").has("terminal_id"))
        try {
            instrumentation.runOnMainSync {
                vm!!.selected = target.id; vm!!.persist()
                val args = Bundle().apply { putString("chat_id", target.id); putString("epoch", DebugWebBridge.epoch) }
                assertTrue(DebugWebBridge.call(vm!!, "web_delete", args).getBoolean("accepted"))
            }
            var finished = false
            repeat(150) {
                if (!finished) {
                    instrumentation.runOnMainSync { finished = target.id !in vm!!.deletingChats }
                    if (!finished) Thread.sleep(100)
                }
            }
            assertTrue("Deletion did not complete", finished)
            instrumentation.runOnMainSync {
                assertFalse(vm!!.deletionError, vm!!.sessions.any { it.id == target.id })
                assertEquals(vm!!.personalSession().id, vm!!.selected)
                assertTrue(vm!!.sessions.any { it.id == neighbor.id })
                assertEquals(modelKeyHash, vm!!.store.config().key.hashCode())
                val fallback = DebugWebBridge.call(vm!!, "web_state", Bundle().apply { putString("chat_id", target.id) })
                assertEquals(vm!!.personalSession().id, fallback.getString("chat_id"))
                assertTrue(vm!!.deleteSession(target.id)) // A delayed duplicate cannot delete another conversation.
                assertTrue(runCatching { DebugWebBridge.call(vm!!, "web_delete", Bundle().apply {
                    putString("chat_id", vm!!.personalSession().id); putString("epoch", DebugWebBridge.epoch)
                }) }.isFailure)
            }
            assertFalse(File(context.filesDir, "sessions/${target.id}").exists())
            assertFalse(File(context.filesDir, "context-history/${target.id}").exists())
            assertFalse(File(context.filesDir, "web-retrieval/${target.id}").exists())
            assertEquals("keep", keep.readText())
            val terminalRecords = org.json.JSONArray(File(context.filesDir, "terminals.json").readText())
            assertFalse((0 until terminalRecords.length()).any { terminalRecords.getJSONObject(it).getString("chat_id") == target.id })
            val persisted = vm!!.store.load()
            assertFalse(persisted.any { it.id == target.id })
        } finally {
            instrumentation.runOnMainSync { vm!!.sessions.removeAll { it.id == target.id || it.id == neighbor.id }; vm!!.select(original); vm!!.persist() }
            kotlinx.coroutines.runBlocking { vm!!.terminals.deleteChat(target.id) }
            SessionDeletion(context.filesDir).cleanup(target.id); SessionDeletion(context.filesDir).cleanup(neighbor.id)
        }
    }
}
