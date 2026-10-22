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
class DebugBridgeDeviceTest {
    @Test fun realMessagePathIsIdempotentAndDoesNotSwitchVisibleChat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Launch as the shell, like the CLI: Android may block background launches from a test process.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ai.capri.app/.MainActivity")).use { it.readBytes() }
        var vm: CapriModel? = null
        repeat(60) {
            if (vm == null) {
                instrumentation.runOnMainSync {
                    val activity = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED).flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filterIsInstance<MainActivity>().firstOrNull()
                    if (activity != null) vm = ViewModelProvider(activity)[CapriModel::class.java]
                }
                if (vm == null) Thread.sleep(100)
            }
        }
        assertNotNull(vm)
        fun call(method: String, extras: Bundle) = JSONObject(context.contentResolver.call(
            Uri.parse("content://ai.capri.app.debug"), method, null, extras)!!.getString("json")!!)
        val id = UUID.randomUUID().toString()
        val args = Bundle().apply { putString("request_id", id); putString("text", "你是谁") }
        val previous = vm!!.selected
        val result = call("send", args)
        assertFalse(result.toString(), result.has("error"))
        val chat = result.getString("chat_id")
        try {
            assertFalse(result.getBoolean("running"))
            assertEquals(2, result.getJSONArray("entries").length())
            assertEquals(IdentityReply.TEXT, result.getJSONArray("entries").getJSONObject(1).getString("text"))
            assertEquals(previous, vm!!.selected)
            val again = call("send", args)
            assertEquals(chat, again.getString("chat_id"))
            assertEquals(2, again.getJSONArray("entries").length())
            val encoded = android.util.Base64.encodeToString(JSONObject().put("request_id", id).put("offset", 1).toString().toByteArray(), android.util.Base64.NO_WRAP)
            val payloadStatus = call("status", Bundle().apply { putString("payload", encoded) })
            assertEquals(1, payloadStatus.getJSONArray("entries").length())
            assertEquals(IdentityReply.TEXT, payloadStatus.getJSONArray("entries").getJSONObject(0).getString("text"))
            val conflict = call("send", Bundle(args).apply { putString("text", "different") })
            assertTrue(conflict.has("error"))
            val invalid = call("status", Bundle().apply { putString("request_id", id); putInt("offset", -1) })
            assertTrue(invalid.has("error"))
            val missing = call("status", Bundle().apply { putString("request_id", UUID.randomUUID().toString()) })
            assertTrue(missing.has("error"))
        } finally {
            instrumentation.runOnMainSync { vm!!.sessions.removeAll { it.id == chat }; vm!!.persist() }
        }
    }
}
