package ai.capri.app

import ai.capri.app.extensions.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ToolsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun id() = "test-legacy-${UUID.randomUUID().toString().take(8)}"
    @Test fun legacyDefinitionsAndInterruptedSwitchMigrateWithoutDataLoss() {
        // Use isolated app files and prefs, never rewrite a user's production definitions during fixture setup.
        val fixtureRoot = File(context.cacheDir, "migration-${UUID.randomUUID()}").apply { mkdirs() }
        val prefsPrefix = "migration-${UUID.randomUUID()}-"
        val fixture = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): File = fixtureRoot
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefsPrefix + name, mode)
        }
        val root = File(fixtureRoot, "extensions").apply { mkdirs() }
        val stable = "pack-${id()}"; val rollback = "pack-${id()}"; val committed = "pack-${id()}"
        fun config(id: String, hash: String) = JSONObject().put("id", id).put("name", "Migration fixture").put("type", "local")
            .put("command", JSONArray(listOf("python", "\${extension}/server.py"))).put("enabled", true)
            .put("toolpack", JSONObject().put("id", id.removePrefix("pack-")).put("sha256", hash))
        val oldRollback = config(rollback, "old"); val newRollback = config(rollback, "new"); val published = config(committed, "new")
        try {
            File(root, "mcp.json").writeText(JSONArray().put(config(stable, "stable").put("enabled", false).put("description", "请在工具包凭据页绑定Key")).put(oldRollback).put(published).toString())
            for (id in listOf(stable, rollback, committed)) {
                File(root, "mcp/$id/current").mkdirs()
                File(root, "mcp/$id/current/server.py").writeText(if (id == stable) "stable-source" else "new-source")
                File(root, "mcp/$id/current/venv").mkdirs(); File(root, "mcp/$id/current/venv/keep.txt").writeText("dependency")
            }
            for ((id, config) in listOf(rollback to newRollback, committed to published)) {
                File(root, "mcp/$id/current.previous").mkdirs(); File(root, "mcp/$id/current.previous/server.py").writeText("old-source")
                File(root, "mcp/$id/.toolpack-transaction.json").writeText(JSONObject().put("config", config).put("had_previous", true).toString())
            }
            File(fixtureRoot, "sessions/keep/workspace").mkdirs(); File(fixtureRoot, "sessions/keep/workspace/artifact.txt").writeText("user-artifact")
            // Initialize encrypted credentials without relying on a live MCP server.
            fixture.getSharedPreferences("extension-secrets", 0).edit().putString(stable, "opaque-existing-ciphertext").commit()
            val migrated = ExtensionStore(fixture)
            assertEquals(3, migrated.configs().size)
            assertTrue(migrated.configs().none { it.has("toolpack") })
            assertFalse(migrated.config(stable).getBoolean("enabled"))
            assertEquals("请在MCP 配置页绑定Key", migrated.config(stable).getString("description"))
            assertEquals("stable-source", File(root, "mcp/$stable/current/server.py").readText())
            assertEquals("dependency", File(root, "mcp/$stable/current/venv/keep.txt").readText())
            assertEquals("old-source", File(root, "mcp/$rollback/current/server.py").readText())
            assertEquals("new-source", File(root, "mcp/$committed/current/server.py").readText())
            assertEquals("opaque-existing-ciphertext", fixture.getSharedPreferences("extension-secrets", 0).getString(stable, null))
            assertEquals("user-artifact", File(fixtureRoot, "sessions/keep/workspace/artifact.txt").readText())
            assertEquals(migrated.configs().map { it.toString() }, ExtensionStore(fixture).configs().map { it.toString() })
            assertTrue(migrated.configs().all { !it.has("toolpack") })
        } finally {
            fixtureRoot.deleteRecursively()
            for (name in listOf("extensions", "extension-secrets")) context.deleteSharedPreferences(prefsPrefix + name)
        }
    }
    @Test fun toolsPanelUsesActualBuiltinsAndConnectedMcp() = runBlocking {
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard", "am start -W -n ai.capri.app/.MainActivity")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        var activity: MainActivity? = null
        repeat(60) {
            if (activity == null) instrumentation.runOnMainSync { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull { !it.isFinishing && !it.isDestroyed } }
            if (activity == null) delay(100)
        }
        val screen = activity ?: error("Activity not resumed")
        lateinit var vm: CapriModel
        instrumentation.runOnMainSync { vm = ViewModelProvider(screen)[CapriModel::class.java] }
        val chat = vm.selected; val otherChat = "test-catalog-${UUID.randomUUID()}"; val server = id()
        val source = vm.store.workspace.file(chat, "$server-source").apply { mkdirs() }
        val tools = listOf("fixture_echo", "fixture_extra")
        File(source, "server.py").writeText("""
import json,sys
for line in sys.stdin:
    request=json.loads(line)
    if 'id' not in request: continue
    method=request['method']
    if method=='initialize': result={'protocolVersion':'2025-03-26','capabilities':{'tools':{}},'serverInfo':{'name':'Catalog fixture','version':'1'}}
    elif method=='tools/list': result={'tools':[{'name':name,'description':'Catalog fixture echo','inputSchema':{'type':'object','properties':{'text':{'type':'string'}},'required':['text']}} for name in ['fixture_echo','fixture_extra']]}
    elif method=='tools/call': result={'content':[{'type':'text','text':request['params']['arguments']['text']}]}
    else: result={}
    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
""".trimIndent())
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, text: (String) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null || node.packageName?.toString() != "ai.capri.app") return null
            if (text(node.text?.toString().orEmpty())) return node
            for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
            return null
        }
        suspend fun await(text: (String) -> Boolean): android.view.accessibility.AccessibilityNodeInfo {
            repeat(60) { instrumentation.uiAutomation.clearCache(); find(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }; delay(100) }
            error("Missing catalog UI control")
        }
        suspend fun click(text: (String) -> Boolean) {
            var node: android.view.accessibility.AccessibilityNodeInfo? = await(text)
            while (node != null && !node.isClickable) node = node.parent
            assertTrue(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            vm.extensions.store.installConfig(JSONObject().put("id", server).put("name", "Catalog fixture").put("type", "local").put("enabled", true)
                .put("tools", JSONArray(listOf(tools.first()))).put("command", JSONArray(listOf("/usr/bin/python3", "-u", "\${extension}/server.py"))), source)
            vm.extensions.activate(chat, server)
            assertEquals(1, vm.extensions.specifications(chat).count { it.getJSONObject("function").getString("name") == ExtensionManager.toolName(server, tools.first()) })
            assertTrue(vm.extensions.specifications(otherChat).isEmpty())
            val catalog = ToolsCatalog.entries(vm.builtinTools(), vm.extensions.specifications(chat), { toolLabel(it, null) }, { vm.extensions.label(chat, it) })
            val builtinNames = (0 until vm.builtinTools().length()).map { vm.builtinTools().getJSONObject(it).getJSONObject("function").getString("name") }
            assertEquals(builtinNames, catalog.filter { !it.mcp }.map { it.name })
            assertFalse(catalog.any { it.description.contains("install_toolpack") })
            assertEquals("fixture-value", JSONObject(vm.extensions.call(chat, ExtensionManager.toolName(server, tools.first()), JSONObject().put("text", "fixture-value"))).getJSONArray("content").getJSONObject(0).getString("text"))
            click { it == "运行" }; click { it == "能力与扩展 · Tools / MCP / Skills" }
            await { it.startsWith("Tools ·") }; await { it.startsWith("13 个内置") }
            click { it == "MCP 工具" }; await { it.contains("fixture_echo") }
            click { it == "用途与参数" }; await { it == "参数定义" }; await { it.contains("\"required\"") && it.contains("\"text\"") }
            click { it == "返回工具列表" }
            vm.extensions.deactivate(chat, server)
            await { it.startsWith("当前会话尚无已连接的 MCP 工具") }
            instrumentation.uiAutomation.clearCache()
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.startsWith("工具包 ·") })
        } finally {
            vm.extensions.deactivate(chat, server); vm.extensions.store.removeConfig(server)
            RuntimeFiles.delete(File(vm.extensions.store.root, "mcp/$server")); RuntimeFiles.delete(source)
            RuntimeFiles.delete(File(vm.store.workspace.root(chat), "extensions/$server"))
        }
    }
}
