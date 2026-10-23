package ai.capri.app.nativebridge

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDeviceTest {
    @Test fun networkCheckReportsEvidenceAndReusesShortCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val tool = AndroidTool(context)
        val args = JSONObject().put("action", "network_check").put("params", JSONObject().put("url", "https://www.baidu.com/private?q=secret"))
        val first = JSONObject(tool.execute(args))
        assertEquals(first.toString(), "ok", first.getString("status"))
        val data = first.getJSONObject("data")
        assertEquals(3, data.getJSONArray("checks").length())
        assertEquals("https://www.baidu.com/", data.getJSONObject("target").getString("origin"))
        assertFalse(data.toString().contains("secret"))
        assertTrue(data.getString("international_samples") in setOf("reachable", "partial", "unreachable"))
        val second = JSONObject(tool.execute(args)).getJSONObject("data").getJSONArray("checks")
        for (i in 0 until second.length()) assertTrue(second.getJSONObject(i).getBoolean("cached"))
        val bad = JSONObject(tool.execute(JSONObject().put("action", "network_check").put("params", JSONObject().put("url", "file:///secret"))))
        assertEquals("invalid_argument", bad.getString("status"))
    }
    @Test fun nativeQueriesRunWithoutPRootOrPrivileges() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val tool = AndroidTool(context)
        for (action in listOf("capabilities", "permissions", "device_info", "battery_info", "storage_info", "display_info", "network_info", "sensors_list", "audio_info", "notifications_info", "apps_list")) {
            val response = JSONObject(tool.execute(JSONObject().put("action", action)))
            assertEquals("$action: $response", "ok", response.getString("status"))
        }
        val wifi = JSONObject(tool.execute(JSONObject().put("action", "wifi_info")))
        assertTrue(wifi.toString(), wifi.getString("status") in setOf("ok", "partial", "unsupported"))
        val invalid = JSONObject(tool.execute(JSONObject().put("action", "wifi_info").put("params", JSONObject().put("command", "bad"))))
        assertEquals("invalid_argument", invalid.getString("status"))
        val unknown = JSONObject(tool.execute(JSONObject().put("action", "shell_exec")))
        assertEquals("unsupported", unknown.getString("status"))
        val openWithoutHost = JSONObject(tool.execute(JSONObject().put("action", "open_url").put("params", JSONObject().put("url", "https://example.com"))))
        assertEquals("foreground_required", openWithoutHost.getString("status"))
        // Read actions should not trigger permission dialogs as a side effect.
        assertNull(tool.permissions.pending.value)
    }
}
