package ai.capri.app

import ai.capri.app.extensions.*
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ToolPackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun source(workspace: Workspace, chat: String, id: String, version: String): File {
        val directory = workspace.file(chat, "source").apply { mkdirs() }
        val manifest = JSONObject().put("schema_version", 1).put("id", id).put("name", "Toolpack fixture")
            .put("version", version).put("source", "https://example.com/capri-toolpack-fixture")
            .put("mcp", JSONObject().put("type", "local").put("command", JSONArray(listOf("/usr/bin/python3", "-u", "\${extension}/server.py"))))
        directory.resolve("toolpack.json").writeText(manifest.toString())
        directory.resolve("server.py").writeText("""
import json,sys,time
from pathlib import Path
VERSION='$version'
for line in sys.stdin:
    request=json.loads(line)
    if 'id' not in request: continue
    method=request['method']
    if method=='initialize': result={'protocolVersion':'2025-03-26','capabilities':{'tools':{}},'serverInfo':{'name':'Package fixture','version':VERSION}}
    elif method=='tools/list': result={'tools':[{'name':'add','description':'Add two numbers','inputSchema':{'type':'object','properties':{'a':{'type':'number'},'b':{'type':'number'},'wait':{'type':'boolean'}},'required':['a','b'],'additionalProperties':False}}]}
    elif method=='tools/call':
        args=request['params']['arguments']
        if args.get('wait'):
            Path('/workspace/pack-started').touch()
            time.sleep(1)
        if not isinstance(args.get('a'),(int,float)) or not isinstance(args.get('b'),(int,float)):
            result={'isError':True,'content':[{'type':'text','text':'Expected two numbers'}]}
        else: result={'content':[{'type':'text','text':json.dumps({'sum':args['a']+args['b'],'version':VERSION})}]}
    else: result={}
    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
""".trimIndent())
        return directory
    }
    private fun activity(): MainActivity {
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard", "am start -W -n ai.capri.app/.MainActivity")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        repeat(60) {
            var result: MainActivity? = null
            instrumentation.runOnMainSync { result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull { !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() } }
            result?.let { return it }; Thread.sleep(100)
        }
        error("Activity unavailable")
    }
    @Test fun installCallUpgradeDisableUninstallAndPanel() = runBlocking {
        val workspace = Store(context).workspace
        val manager = ExtensionManager(context, PythonRuntime(context, workspace), workspace)
        val id = "test-pack-${UUID.randomUUID().toString().take(8)}"; val mcpId = ToolPackPolicy.mcpId(id)
        val first = "test-pack-${UUID.randomUUID()}"; val second = "test-pack-${UUID.randomUUID()}"
        val before = manager.store.configs().map { it.getString("id") }.toSet()
        var activity: MainActivity? = null
        suspend fun run(a: Any = 2, wait: Boolean = false) = JSONObject(manager.call(first, ExtensionManager.toolName(mcpId, "add"), JSONObject().put("a", a).put("b", 3).put("wait", wait)))
        fun content(result: JSONObject) = JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"))
        try {
            val directory = source(workspace, first, id, "1.0.0")
            val zip = workspace.file(first, "package.zip")
            ZipOutputStream(zip.outputStream()).use { output -> directory.walkTopDown().filter { it.isFile }.forEach { output.putNextEntry(ZipEntry(it.relativeTo(directory).invariantSeparatorsPath)); output.write(it.readBytes()); output.closeEntry() } }
            val installed = JSONObject(manager.execute(first, JSONObject().put("action", "install_toolpack").put("path", "package.zip")))
            assertEquals("installed", installed.getString("status")); assertEquals(64, installed.getJSONObject("toolpack").getString("sha256").length)
            manager.activate(first, mcpId); manager.activate(second, mcpId)
            assertEquals(5, content(run()).getInt("sum"))
            assertTrue(run("invalid").getBoolean("isError"))
            assertFalse(manager.specifications(first).single().getJSONObject("function").getJSONObject("parameters").getBoolean("additionalProperties"))
            val slow = async(Dispatchers.IO) { run(wait = true) }
            withTimeout(5000) { while (!workspace.file(first, "pack-started").exists()) delay(30) }
            source(workspace, first, id, "2.0.0")
            val upgrading = async(Dispatchers.IO) { manager.execute(first, JSONObject().put("action", "upgrade_toolpack").put("path", "source").put("id", id)) }
            delay(150); assertFalse(upgrading.isCompleted)
            assertEquals("1.0.0", content(slow.await()).getString("version")); upgrading.await()
            assertTrue(manager.specifications(first).isEmpty()); assertTrue(manager.specifications(second).isEmpty())
            assertFalse(manager.store.active(second, mcpId))
            manager.activate(first, mcpId); assertEquals("2.0.0", content(run()).getString("version"))
            manager.execute(first, JSONObject().put("action", "enable_toolpack").put("id", id).put("enabled", false))
            assertTrue(runCatching { manager.activate(second, mcpId) }.isFailure)
            source(workspace, first, id, "3.0.0")
            manager.execute(first, JSONObject().put("action", "upgrade_toolpack").put("path", "source").put("id", id))
            assertFalse(manager.store.config(mcpId).getBoolean("enabled"))
            val sha = manager.store.config(mcpId).getJSONObject("toolpack").getString("sha256")
            assertTrue(runCatching { manager.execute(first, JSONObject().put("action", "upgrade_toolpack").put("path", "source")) }.isFailure)
            assertEquals(sha, manager.store.config(mcpId).getJSONObject("toolpack").getString("sha256"))
            directory.resolve("toolpack.json").writeText("{}")
            assertTrue(runCatching { manager.execute(first, JSONObject().put("action", "upgrade_toolpack").put("path", "source")) }.isFailure)
            assertEquals("3.0.0", JSONObject(File(manager.store.root, "mcp/$mcpId/current/toolpack.json").readText()).getString("version"))
            manager.execute(first, JSONObject().put("action", "enable_toolpack").put("id", id).put("enabled", true))
            manager.activate(first, mcpId); assertEquals("3.0.0", content(run()).getString("version"))
            manager.deactivate(first, mcpId)
            // Exercise the actual Compose management tab against the installed metadata.
            val screen = activity(); activity = screen
            lateinit var vm: CapriModel
            instrumentation.runOnMainSync { vm = ViewModelProvider(screen)[CapriModel::class.java]; screen.setContent { MaterialTheme { ExtensionsPanel(vm) {} } } }
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?, predicate: (String) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null || node.packageName?.toString() != "ai.capri.app") return null
                if (predicate(node.text?.toString().orEmpty())) return node
                for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
                return null
            }
            var tab: android.view.accessibility.AccessibilityNodeInfo? = null
            repeat(30) { if (tab == null) { instrumentation.uiAutomation.clearCache(); tab = find(instrumentation.uiAutomation.rootInActiveWindow) { it.startsWith("工具包 ·") }; if (tab == null) delay(100) } }
            assertNotNull(tab)
            var clickable = tab
            while (clickable != null && !clickable!!.isClickable) clickable = clickable!!.parent
            assertTrue(clickable!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            delay(300)
            fun scrollable(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null || node.packageName?.toString() != "ai.capri.app") return null
                if (node.isScrollable) return node
                for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
                return null
            }
            repeat(10) {
                instrumentation.uiAutomation.clearCache()
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (find(root) { it.contains("$id · 3.0.0") } == null) {
                    scrollable(root)?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); delay(100)
                }
            }
            instrumentation.uiAutomation.clearCache()
            assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.contains("$id · 3.0.0") })
            assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it == "卸载" })
            workspace.file(first, "keep.txt").writeText("Keep workspace artifact")
            manager.store.saveSecret(mcpId, "fixture-only-secret")
            manager.execute(first, JSONObject().put("action", "uninstall_toolpack").put("id", id))
            assertFalse(File(manager.store.root, "mcp/$mcpId").exists()); assertFalse(manager.store.hasSecret(mcpId))
            for (chat in listOf(first, second)) assertFalse(File(workspace.folder(chat, "extensions"), mcpId).exists())
            assertEquals("Keep workspace artifact", workspace.file(first, "keep.txt").readText())
            assertEquals(before, manager.store.configs().map { it.getString("id") }.toSet())
        } finally {
            manager.dispose()
            if (manager.store.configs().any { it.getString("id") == mcpId }) manager.store.removeToolPack(id)
            RuntimeFiles.delete(workspace.root(first)); RuntimeFiles.delete(workspace.root(second))
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
        }
    }
    @Test fun interruptedUpgradeRollsBackOrFinishesAndInterruptedRemovalResumes() {
        val store = ExtensionStore(context); val workspace = Store(context).workspace
        val id = "test-recover-${UUID.randomUUID().toString().take(8)}"; val mcpId = ToolPackPolicy.mcpId(id)
        val chat = "test-pack-recover-${UUID.randomUUID()}"
        val directory = File(store.root, "mcp/$mcpId")
        try {
            store.installToolPack(source(workspace, chat, id, "1.0.0"), false)
            val old = store.config(mcpId)
            File(directory, ".toolpack-remove.json.new").writeText("incomplete atomic write")
            val unchanged = ExtensionStore(context); unchanged.checkToolPackReady(mcpId)
            assertFalse(File(directory, ".toolpack-remove.json.new").exists())
            fun stageUpgrade(commit: Boolean) {
                val src = source(workspace, chat, id, "2.0.0")
                check(File(directory, "current").renameTo(File(directory, "current.previous")))
                ExtensionInstall.copyDirectory(src, File(directory, "current"))
                val next = ToolPackPolicy.config(ToolPackPolicy.manifest(src), true).put("toolpack", JSONObject(old.getJSONObject("toolpack").toString()).put("version", "2.0.0").put("sha256", ToolPackPolicy.hash(src)))
                File(directory, ".toolpack-transaction.json").writeText(JSONObject().put("config", next).put("had_previous", true).toString())
                if (commit) store.saveConfig(next)
            }
            // A first install interrupted before config publication must not leave an ID-blocking orphan.
            val freshId = "$id-new"; val freshMcpId = ToolPackPolicy.mcpId(freshId)
            val freshDirectory = File(store.root, "mcp/$freshMcpId").apply { mkdirs() }
            val freshSource = source(workspace, chat, freshId, "1.0.0")
            ExtensionInstall.copyDirectory(freshSource, File(freshDirectory, "current.next"))
            val freshConfig = ToolPackPolicy.config(ToolPackPolicy.manifest(freshSource), true).put("toolpack", JSONObject().put("id", freshId).put("sha256", ToolPackPolicy.hash(freshSource)))
            File(freshDirectory, ".toolpack-transaction.json").writeText(JSONObject().put("config", freshConfig).put("had_previous", false).toString())
            val restarted = ExtensionStore(context); assertFalse(freshDirectory.exists())
            restarted.installToolPack(freshSource, false); restarted.removeToolPack(freshId)
            stageUpgrade(false)
            val rolledBack = ExtensionStore(context)
            assertEquals("1.0.0", rolledBack.config(mcpId).getJSONObject("toolpack").getString("version"))
            assertEquals("1.0.0", JSONObject(File(directory, "current/toolpack.json").readText()).getString("version"))
            stageUpgrade(true)
            val completed = ExtensionStore(context)
            assertEquals("2.0.0", completed.config(mcpId).getJSONObject("toolpack").getString("version"))
            assertFalse(File(directory, "current.previous").exists()); assertFalse(File(directory, ".toolpack-transaction.json").exists())
            File(directory, ".toolpack-remove.json").writeText(JSONObject().put("id", id).toString())
            val removed = ExtensionStore(context)
            assertTrue(removed.configs().none { it.getString("id") == mcpId }); assertFalse(directory.exists())
        } finally {
            if (store.configs().any { it.getString("id") == mcpId }) store.removeToolPack(id)
            RuntimeFiles.delete(workspace.root(chat)); RuntimeFiles.delete(directory)
        }
    }
}
