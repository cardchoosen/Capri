package ai.capri.app

import ai.capri.app.extensions.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit real-mcp profile only: public read-only services; successful definitions stay installed. */
@RunWith(AndroidJUnit4::class)
class RealMcpDeviceTest {
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun install(manager: ExtensionManager, workspace: Workspace, chat: String, config: JSONObject) = runBlocking {
        val existing = manager.store.configs().firstOrNull { it.getString("id") == config.getString("id") }
        if (existing == null) {
            workspace.file(chat, "service.json").writeText(config.toString())
            manager.execute(chat, JSONObject().put("action", "install_mcp").put("path", "service.json"))
        } else {
            for (key in listOf("type", "url", "command", "python_packages")) assertEquals("Existing service differs: $key", config.opt(key)?.toString(), existing.opt(key)?.toString())
            assertTrue("Existing service disabled; do not change user preference", existing.getBoolean("enabled"))
        }
    }
    @Test fun remoteDeepWiki() = runBlocking {
        val context = context(); val workspace = Store(context).workspace
        val manager = ExtensionManager(context, PythonRuntime(context, workspace), workspace)
        val chat = "real-deepwiki-${UUID.randomUUID()}"
        try {
            install(manager, workspace, chat, JSONObject("""{"id":"deepwiki","name":"DeepWiki","type":"remote","url":"https://mcp.deepwiki.com/mcp","enabled":true,"timeout_seconds":90}"""))
            manager.activate(chat, "deepwiki")
            assertTrue(manager.specifications(chat).any { it.getJSONObject("function").getString("name") == ExtensionManager.toolName("deepwiki", "read_wiki_structure") })
            val result = JSONObject(manager.call(chat, ExtensionManager.toolName("deepwiki", "read_wiki_structure"), JSONObject().put("repoName", "modelcontextprotocol/python-sdk")))
            assertFalse(result.toString().take(1000), result.optBoolean("isError"))
            assertFalse(result.toString().take(1000), result.optString("status") == "mcp_call_failed")
            val content = result.getJSONArray("content").toString()
            assertTrue(content.take(1000), content.contains("#") || content.contains("SDK"))
            assertTrue(content.length > 80)
        } finally { manager.deactivate(chat, "deepwiki"); manager.dispose(); RuntimeFiles.delete(workspace.root(chat)) }
    }
    @Test fun remoteContext7Public() = runBlocking {
        val context = context(); val workspace = Store(context).workspace
        val manager = ExtensionManager(context, PythonRuntime(context, workspace), workspace)
        val chat = "real-context7-${UUID.randomUUID()}"
        try {
            install(manager, workspace, chat, JSONObject("""{"id":"context7-public","name":"Context7 (anonymous)","type":"remote","url":"https://mcp.context7.com/mcp","enabled":true,"timeout_seconds":90}"""))
            manager.activate(chat, "context7-public")
            val result = JSONObject(manager.call(chat, ExtensionManager.toolName("context7-public", "resolve-library-id"), JSONObject().put("libraryName", "fastapi").put("query", "FastAPI path operation parameter documentation")))
            assertFalse(result.toString().take(1000), result.optBoolean("isError"))
            assertTrue(result.toString().take(1000), result.toString().contains("fastapi", ignoreCase = true))
        } finally { manager.deactivate(chat, "context7-public"); manager.dispose(); RuntimeFiles.delete(workspace.root(chat)) }
    }
    @Test fun localOfficialTime() = runBlocking {
        val context = context(); val workspace = Store(context).workspace
        val manager = ExtensionManager(context, PythonRuntime(context, workspace), workspace)
        val chat = "real-time-${UUID.randomUUID()}"
        try {
            install(manager, workspace, chat, JSONObject("""{"id":"mcp-time","name":"Time","type":"local","command":["python","-m","mcp_server_time","--local-timezone=Asia/Shanghai"],"python_packages":["mcp-server-time==2026.10.10"],"enabled":true,"timeout_seconds":60}"""))
            // Optional development-only wheelhouse: no change to the service definition or production installer.
            // Record this path separately from direct phone/PyPI installation in the acceptance report.
            val wheels = java.io.File(context.filesDir, "runtime/package-cache/pip/wheels")
            if (wheels.listFiles().orEmpty().any { it.extension == "whl" }) {
                val runtime = PythonRuntime(context, workspace); runtime.ensureSession(chat)
                val extension = java.io.File(manager.store.root, "mcp/mcp-time/current").apply { mkdirs() }
                for (command in listOf(listOf("/usr/bin/python3", "-m", "venv", "/extension/venv"), listOf("/extension/venv/bin/python", "-m", "pip", "install", "--no-index", "--find-links", "/opt/capri/cache/pip/wheels", "mcp-server-time==2026.10.10"))) {
                    val run = workspace.folder(chat, "runs").resolve(java.util.UUID.randomUUID().toString()).apply { mkdirs() }
                    val process = runtime.startExtensionProcess(chat, extension, run, command)
                    val output = async(Dispatchers.IO) { process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }
                    val errors = async(Dispatchers.IO) { process.errorStream.use { it.readBytes().toString(Charsets.UTF_8) } }
                    try { withTimeout(120_000) { while (process.isAlive) delay(50) }; assertEquals((output.await() + errors.await()).takeLast(2000), 0, process.exitValue()) }
                    finally { OwnedProcesses.stop(process); output.cancel(); errors.cancel() }
                }
            }
            manager.activate(chat, "mcp-time")
            val current = JSONObject(manager.call(chat, ExtensionManager.toolName("mcp-time", "get_current_time"), JSONObject().put("timezone", "Asia/Shanghai")))
            assertFalse(current.toString().take(1000), current.optBoolean("isError"))
            assertTrue(current.toString().take(1000), current.toString().contains("Asia\\/Shanghai") || current.toString().contains("Asia/Shanghai"))
            val conversion = JSONObject(manager.call(chat, ExtensionManager.toolName("mcp-time", "convert_time"), JSONObject().put("source_timezone", "UTC").put("time", "12:00").put("target_timezone", "Asia/Shanghai")))
            assertFalse(conversion.toString().take(1000), conversion.optBoolean("isError"))
            assertTrue(conversion.toString().take(1000), conversion.toString().contains("20:00"))
        } finally { manager.deactivate(chat, "mcp-time"); manager.dispose(); RuntimeFiles.delete(workspace.root(chat)) }
    }
}
