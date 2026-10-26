package ai.capri.app

import ai.capri.app.model.*
import android.os.Bundle
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ModelsDeviceTest {
    @Test fun encryptedMultiAccountStorageAndSelectionSurviveReload() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "model-fixture-${UUID.randomUUID()}"
        val fixture = object : ContextWrapper(target) {
            override fun getSharedPreferences(n: String, mode: Int) = target.getSharedPreferences("$name-$n", mode)
            override fun getFilesDir(): File = File(target.cacheDir, name).apply { mkdirs() }
        }
        try {
            var store = Store(fixture)
            val a = ModelAccount(provider = "deepseek", name = "fixture", endpoint = "https://example.com/v1", key = "DO-NOT-PERSIST-PLAINTEXT", protocol = ModelProtocol.CHAT,
                models = listOf(ModelOption("model", context = 1_048_576)))
            store.models.save(a); store.models.select("chat-a", a.id, "model")
            val raw = fixture.getSharedPreferences("config", Context.MODE_PRIVATE).getString("model_accounts", "")!!
            assertFalse(raw.contains(a.key)); assertFalse(raw.contains("model_accounts"))
            store = Store(fixture)
            assertEquals(a.key, store.config("chat-a").key)
            assertEquals(1_048_576, store.config("chat-a").contextWindow)
            store.models.remove(a.id)
            assertTrue(Store(fixture).config("chat-a").key.isBlank())
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, keyStore.getKey("capri-model", null)) }
            val token = android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP) + ":" + android.util.Base64.encodeToString(cipher.doFinal("legacy-secret".toByteArray()), android.util.Base64.NO_WRAP)
            fixture.getSharedPreferences("config", Context.MODE_PRIVATE).edit().remove("model_accounts").putString("token", token)
                .putString("endpoint", "https://api.deepseek.com/v1").putString("model", "deepseek-flash").commit()
            val migrated = Store(fixture)
            assertEquals("legacy-secret", migrated.config("migrated-chat").key)
            assertFalse(fixture.getSharedPreferences("config", Context.MODE_PRIVATE).contains("token"))
            assertEquals("models_registry", migrated.config("migrated-chat").contextSource)
        } finally { target.deleteSharedPreferences("$name-config"); fixture.filesDir.deleteRecursively() }
    }
    @Test fun modelPickerShowsLogosContextAndSwitchesAndEditorOffersPresets() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val target = instrument.targetContext
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        target.startActivity(android.content.Intent(target, ModelUiTestActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
            return null
        }
        fun await(text: String): android.view.accessibility.AccessibilityNodeInfo {
            repeat(70) {
                instrument.uiAutomation.clearCache()
                find(instrument.uiAutomation.rootInActiveWindow, text)?.let { return it }
                Thread.sleep(100)
            }
            error("Missing UI: $text")
        }
        fun click(text: String) {
            var n: android.view.accessibility.AccessibilityNodeInfo? = await(text)
            while (n != null && !n.isClickable) n = n.parent
            assertTrue(n?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        fun screenshot(name: String) {
            instrument.waitForIdleSync()
            val file = File(target.cacheDir, "model-ui-test/$name.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use { instrument.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        for (provider in Providers.all.filter { it.id != "custom" }) assertTrue(target.resources.getIdentifier("provider_${provider.id}", "drawable", target.packageName) != 0)
        try {
            click("DeepSeek Fixture"); await("1M"); await("128K"); await("上下文未知"); await("DeepSeek"); screenshot("picker")
            click("Gemini Fixture"); await("Gemini Fixture"); await(" · 128K")
            click("Gemini Fixture"); click("管理模型 API"); await("模型 API")
            click("添加 API"); await("API Key"); await("保存并获取模型"); click("DeepSeek")
            await("OpenAI"); await("阿里云百炼 · Qwen")
            shell("input keyevent KEYCODE_BACK")
            click("高级选项"); await("API Base URL"); await("Chat Completions"); screenshot("editor")
            shell("input keyevent KEYCODE_BACK")
        } finally { repeat(4) { shell("input keyevent KEYCODE_BACK") } }
    }
}
