package ai.capri.app

import ai.capri.app.agent.TaskState
import ai.capri.app.agent.ToolDiscovery
import ai.capri.app.knowledge.ContextHistory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real disk persistence under an isolated cache directory. No model requests or user history changes. */
@RunWith(AndroidJUnit4::class)
class AgentHarnessDeviceTest {
    @Test fun atomicTaskRecoveryAndOffloadedFailureRemainReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "agent-harness-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "runtime"))
                .put(JSONObject().put("role", "user").put("content", "Preserve all existing API signatures"))
            TaskState.ensure(messages, "Fix the compiler error", false)
            TaskState.execute(messages, JSONObject().put("action", "update").put("findings", "One compiler error remains"))
            val recovery = AgentRecovery(File(root, "recovery"))
            recovery.save("fixture", messages)
            val restored = requireNotNull(recovery.load("fixture"))
            TaskState.ensure(restored, "continue", true)
            assertEquals("One compiler error remains", TaskState.snapshot(restored).getString("findings"))
            assertTrue(TaskState.snapshot(restored).toString().contains("Preserve all existing API signatures"))
            val archive = ContextHistory(File(root, "history"))
            val full = JSONObject().put("status", "error").put("output", "Compiler diagnostics\n".repeat(5000) + "final failure").toString()
            val result = JSONObject(archive.boundResult("fixture", "terminal", full))
            assertEquals("error", result.getString("status"))
            assertTrue(result.getString("preview_tail").contains("final failure"))
            val reloaded = ContextHistory(File(root, "history"))
            val page = JSONObject(reloaded.query("fixture", emptyList(), JSONObject().put("reference", result.getString("reference"))))
            assertTrue(page.getInt("total_chars") > 40000)
            assertTrue(page.getString("text").contains("Compiler diagnostics"))
        } finally { root.deleteRecursively() }
    }
    @Test fun newToolContractsAndDeferredSurfaceAreEnglish() {
        val discovery = ToolDiscovery()
        val specs = listOf(TaskState.specification()) + discovery.specifications()
        assertEquals(4, specs.size)
        for (spec in specs) {
            val name = spec.getJSONObject("function").getString("name")
            assertFalse(spec.toString().any { it in '\u4e00'..'\u9fff' })
            assertFalse(toolLabel(name, null).any { it in '\u4e00'..'\u9fff' })
        }
        val all = JSONArray(specs).put(ai.capri.app.nativebridge.NativeCatalog.spec())
        assertTrue(discovery.assemble(all).toString().contains("wifi_info"))
        assertTrue(discovery.manifest().toString().contains("Android"))
        discovery.execute("tool_describe", JSONObject().put("names", JSONArray().put("android")))
        assertTrue(discovery.assemble(all).toString().contains("wifi_info"))
    }
}
