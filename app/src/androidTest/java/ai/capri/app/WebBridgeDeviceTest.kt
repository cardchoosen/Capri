package ai.capri.app

import android.net.Uri
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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebBridgeDeviceTest {
    @Test fun webAndPhoneShareHistoryWithoutChangingPhoneSelection() {
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
        fun call(method: String, values: JSONObject = JSONObject()): JSONObject {
            val payload = android.util.Base64.encodeToString(values.toString().toByteArray(), android.util.Base64.NO_WRAP)
            return JSONObject(context.contentResolver.call(Uri.parse("content://ai.capri.app.debug"), method, null,
                Bundle().apply { putString("payload", payload) })!!.getString("json")!!)
        }
        val original = vm!!.selected
        val originalTab = vm!!.activeTab
        instrumentation.runOnMainSync { vm!!.activeTab = 1 }
        val epoch = call("web_state").getString("epoch")
        val create = JSONObject().put("epoch", epoch).put("request_id", UUID.randomUUID().toString())
        val result = call("web_create", create)
        assertFalse(result.toString(), result.has("error"))
        val chat = result.getString("chat_id")
        try {
            assertEquals(chat, call("web_create", create).getString("chat_id"))
            assertEquals(original, vm!!.selected)
            val args = JSONObject().put("epoch", epoch).put("chat_id", chat)
                .put("request_id", UUID.randomUUID().toString()).put("text", "你是谁")
            val sent = call("send", args)
            assertFalse(sent.toString(), sent.has("error"))
            call("send", args)
            val state = call("web_state", JSONObject().put("chat_id", chat))
            assertEquals(2, state.getInt("total"))
            assertEquals(IdentityReply.TEXT, state.getJSONArray("entries").getJSONObject(1).getString("text"))
            instrumentation.runOnMainSync {
                assertEquals(2, vm!!.sessions.first { it.id == chat }.messages.size)
                assertTrue(vm!!.sendTo(chat, "你是谁"))
            }
            assertEquals(4, call("web_state", JSONObject().put("chat_id", chat)).getInt("total"))
            assertEquals(IdentityReply.TEXT, call("web_state", JSONObject().put("chat_id", chat))
                .getJSONArray("entries").getJSONObject(3).getString("text"))
            val page = call("web_state", JSONObject().put("chat_id", chat).put("before", 1))
            assertEquals(1, page.getJSONArray("entries").length())
            assertEquals("user", page.getJSONArray("entries").getJSONObject(0).getString("role"))
            val stale = call("send", JSONObject(args.toString()).put("epoch", "stale").put("request_id", UUID.randomUUID().toString()))
            assertTrue(stale.has("error"))
            assertEquals(4, call("web_state", JSONObject().put("chat_id", chat)).getInt("total"))
            instrumentation.runOnMainSync {
                val args = Bundle().apply { putString("chat_id", chat) }
                val snapshot = DebugWebBridge.call(vm!!, "web_state", args)
                args.putString("epoch", snapshot.getString("epoch"))
                args.putInt("revision", snapshot.getInt("revision"))
                val unchanged = DebugWebBridge.call(vm!!, "web_state", args)
                assertTrue(unchanged.getBoolean("unchanged"))
                assertFalse(unchanged.has("entries"))
            }
            assertEquals(original, vm!!.selected)
        } finally {
            instrumentation.runOnMainSync { vm!!.sessions.removeAll { it.id == chat }; vm!!.activeTab = originalTab; vm!!.persist() }
        }
    }
}
