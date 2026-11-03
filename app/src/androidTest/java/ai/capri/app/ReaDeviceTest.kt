package ai.capri.app

import ai.capri.app.extensions.ExtensionManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ReaDeviceTest {
    @Test fun actualManagedApkProfileAnalyzesOurFixtureAndCleansUp() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val context = ins.targetContext
        val workspace = Workspace(File(context.filesDir, "sessions"))
        val runtime = PythonRuntime(context, workspace)
        val manager = ExtensionManager(context, runtime, workspace)
        val chat = "rea-fixture-${UUID.randomUUID()}"
        val previous = manager.rea.enabled
        try {
            manager.setReaEnabled(chat, false)
            assertTrue(manager.specifications(chat).isEmpty())
            assertTrue(runCatching { manager.activate(chat, "capri-rea-apk") }.isFailure)
            manager.setReaEnabled(chat, true)
            ins.context.assets.open("rea-fixture.apk").use { input -> workspace.file(chat, "fixture.apk").outputStream().use(input::copyTo) }
            manager.activate(chat, "capri-rea-apk")
            val names = manager.specifications(chat).map { it.getJSONObject("function").getString("name") }
            assertTrue(names.contains(ExtensionManager.toolName("capri-rea-apk", "inspect_android_package")))
            val result = manager.call(chat, ExtensionManager.toolName("capri-rea-apk", "inspect_android_package"), JSONObject().put("path", "/workspace/fixture.apk").put("detail", "summary"))
            File(context.cacheDir, "rea-device-result.json").writeText(result)
            assertFalse(result.take(2400), JSONObject(result).optBoolean("isError"))
            assertTrue(result.take(2400), result.contains("ai.capri.fixture"))
            assertFalse(result.take(2400), result.contains("capability_unavailable"))
            val classes = manager.call(chat, ExtensionManager.toolName("capri-rea-apk", "search_android_classes"), JSONObject().put("path", "/workspace/fixture.apk").put("query", "Fixture"))
            assertTrue(classes.take(2400), classes.contains("ai.capri.fixture.Fixture"))
            val evidence = manager.call(chat, ExtensionManager.toolName("capri-rea-apk", "inspect_android_class"), JSONObject().put("path", "/workspace/fixture.apk").put("class_name", "ai.capri.fixture.Fixture"))
            assertTrue(evidence.take(2400), evidence.contains("shippingFee"))
            val resources=manager.call(chat,ExtensionManager.toolName("capri-rea-apk","decode_android_resources"),JSONObject().put("path","/workspace/fixture.apk"))
            File(context.cacheDir,"rea-resources-result.json").writeText(resources)
            assertFalse(resources.take(2000),JSONObject(resources).optBoolean("isError"))
            assertTrue(resources.take(2000),resources.contains("shipping_label"))
            manager.deactivate(chat, "capri-rea-apk")
            assertTrue(manager.specifications(chat).isEmpty())
        } finally {
            workspace.folder(chat,"extensions").walkTopDown().filter { it.name=="stderr.log" }.forEach { log -> File(context.cacheDir,"rea-startup-stderr.txt").writeText(log.readText().takeLast(16000)) }
            manager.deactivate(chat, "capri-rea-apk")
            manager.setReaEnabled(chat, previous)
            manager.deleteChat(chat)
            SessionDeletion(context.filesDir).cleanup(chat)
            activity.close()
        }
    }
    @Test fun actualStaticJavaScriptUsesTheManagedMcp() = runBlocking {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val activity=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val workspace=Workspace(File(context.filesDir,"sessions"));val manager=ExtensionManager(context,PythonRuntime(context,workspace),workspace)
        val chat="rea-js-fixture-${UUID.randomUUID()}";val previous=manager.rea.enabled
        try {
            manager.setReaEnabled(chat,true)
            workspace.file(chat,"fixture.js").writeText("export function shippingFee(subtotal) { return subtotal >= 100 ? 0 : 12; }")
            manager.activate(chat,"capri-rea-javascript")
            fun name(tool:String)=ExtensionManager.toolName("capri-rea-javascript",tool)
            val opened=manager.call(chat,name("open_binary"),JSONObject().put("path","/workspace/fixture.js"))
            assertFalse(opened.take(2000),opened.contains("capability_unavailable"))
            val facts=manager.call(chat,name("inspect_artifact"),JSONObject())
            File(context.cacheDir,"rea-js-result.json").writeText(facts)
            assertFalse(facts.take(2000),JSONObject(facts).optBoolean("isError"))
            assertTrue(facts.take(2000),facts.contains("fixture.js"))
            assertFalse(facts.take(2000),facts.contains("tool_error"))
        } finally { manager.deactivate(chat,"capri-rea-javascript");manager.setReaEnabled(chat,previous);manager.deleteChat(chat);SessionDeletion(context.filesDir).cleanup(chat);activity.close() }
    }

    @Test fun actualGhidraArm64DecompilesOurNativeFixture() = runBlocking {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val activity=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val workspace=Workspace(File(context.filesDir,"sessions"));val runtime=PythonRuntime(context,workspace)
        val manager=ExtensionManager(context,runtime,workspace);val chat="rea-native-fixture-${UUID.randomUUID()}";val previous=manager.rea.enabled
        try {
            manager.setReaEnabled(chat,true);manager.activate(chat,"capri-rea-native")
            workspace.file(chat,"fixture.cpp").writeText("extern \"C\" int shipping_fee(int subtotal) { return subtotal >= 100 ? 0 : 12; } int main() { return shipping_fee(99); }")
            val compiled=runtime.runCommand(chat,listOf("/usr/bin/g++","-O0","-g","/workspace/fixture.cpp","-o","/workspace/fixture"),timeout=60)
            assertTrue(compiled,compiled.startsWith("exit=0"))
            fun name(tool:String)=ExtensionManager.toolName("capri-rea-native",tool)
            val opened=manager.call(chat,name("open_binary"),JSONObject().put("path","/workspace/fixture").put("provider_id","ghidra"))
            File(context.cacheDir,"rea-native-open.json").writeText(opened)
            assertFalse(opened.take(2000),opened.contains("capability_unavailable"))
            val facts=manager.call(chat,name("procedure_pseudo_code"),JSONObject().put("procedure","shipping_fee"))
            File(context.cacheDir,"rea-native-result.json").writeText(facts)
            assertFalse(facts.take(2000),JSONObject(facts).optBoolean("isError"))
            assertTrue(facts.take(2000),facts.contains("return "))
            assertTrue(facts.take(2000),facts.contains("shipping_fee"));assertTrue(facts.take(2000),facts.contains("12")||facts.contains("0xc"))
        } finally {
            workspace.folder(chat,"extensions").walkTopDown().filter { it.name=="stderr.log" }.forEach { File(context.cacheDir,"rea-native-stderr.txt").writeText(it.readText().takeLast(16000)) }
            manager.deactivate(chat,"capri-rea-native");manager.setReaEnabled(chat,previous);manager.deleteChat(chat);SessionDeletion(context.filesDir).cleanup(chat);activity.close()
        }
    }

    @Test fun actualFridaBindingCanDiscoverDevicesWithoutAttaching() = runBlocking {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val activity=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val workspace=Workspace(File(context.filesDir,"sessions"));val manager=ExtensionManager(context,PythonRuntime(context,workspace),workspace)
        val chat="rea-runtime-fixture-${UUID.randomUUID()}";val previous=manager.rea.enabled
        try {
            manager.setReaEnabled(chat,true);manager.activate(chat,"capri-rea-runtime")
            val facts=manager.call(chat,ExtensionManager.toolName("capri-rea-runtime","list_frida_devices"),JSONObject())
            File(context.cacheDir,"rea-runtime-result.json").writeText(facts)
            val result=JSONObject(facts);assertFalse(facts.take(2000),result.optBoolean("isError"))
            val devices=result.getJSONObject("structuredContent").getJSONArray("devices")
            assertTrue((0 until devices.length()).any { devices.getJSONObject(it).optString("device_id")=="local" })
        } finally { manager.deactivate(chat,"capri-rea-runtime");manager.setReaEnabled(chat,previous);manager.deleteChat(chat);SessionDeletion(context.filesDir).cleanup(chat);activity.close() }
    }

}
