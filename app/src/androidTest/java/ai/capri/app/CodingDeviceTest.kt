package ai.capri.app

import ai.capri.app.files.WorkspaceFileTools
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class CodingDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun foreground() = ins.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    @Test fun projectRulesPatchesAndRealVerification() = runBlocking<Unit> {
        foreground()
        val workspace = Store(context).workspace; val runtime = PythonRuntime(context, workspace)
        val tool = WorkspaceFileTools(workspace); val chat = "coding-${UUID.randomUUID()}"
        fun call(name: String, vararg fields: Pair<String, Any>) = JSONObject(tool.execute(chat, name, JSONObject(mapOf(*fields))))
        try {
            runtime.ensureSession(chat)
            workspace.file(chat, "AGENTS.md").writeText("Use the standard library. Preserve the existing addition function. Verify actual behavior.")
            workspace.file(chat, "pyproject.toml").writeText("[project]\nname = \"coding-fixture\"\nversion = \"0.0.1\"\n")
            workspace.file(chat, "mathbox.py").writeText("def add(a, b):\n    return a + b\n")
            val project = call("project")
            assertEquals(project.toString(), "ok", project.getString("status")); assertEquals(1, project.getJSONArray("project_rules").length())
            val patch = "*** Begin Patch\n*** Update File: mathbox.py\n@@\n def add(a, b):\n     return a + b\n+\n+def multiply(a, b):\n+    return a * b\n*** Add File: verify.py\n+from mathbox import add, multiply\n+assert add(2, 3) == 5\n+assert multiply(-2, 3) == -6\n+print('coding fixture verified')\n*** End Patch"
            val preview = call("apply_patch", "patch" to patch, "dry_run" to true)
            assertEquals("ok", preview.getString("status")); assertFalse(workspace.file(chat, "verify.py").exists())
            val applied = call("apply_patch", "patch" to patch)
            assertEquals(applied.toString(), "ok", applied.getString("status")); assertEquals(2, applied.getJSONArray("applied_paths").length())
            val result = JSONObject(ShortTaskTool(runtime, CapabilityTool(context, runtime)).execute(chat, JSONObject().put("steps", org.json.JSONArray().put(JSONObject().put("name", "verify").put("argv", org.json.JSONArray(listOf("python3", "/workspace/verify.py")))))))
            assertEquals(result.toString(), "ok", result.getString("status")); assertTrue(result.toString().contains("coding fixture verified"))
            tool.beginTurn(chat)
            assertEquals("project_rules_required", call("edit_file", "path" to "mathbox.py", "old_text" to "a * b", "new_text" to "a / b").getString("status"))
            assertTrue(workspace.file(chat, "mathbox.py").readText().contains("a * b"))
        } finally { RuntimeFiles.delete(workspace.root(chat)) }
    }
    @Test fun prepareWebFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("keep_coding_fixture") == "true")
        foreground()
        var vm: CapriModel? = null
        repeat(60) { if (vm == null) { ins.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.let { vm = ViewModelProvider(it)[CapriModel::class.java] } }; if (vm == null) Thread.sleep(100) } }
        val model = requireNotNull(vm); val session = Session(title = "通用编程能力验收")
        val work = model.store.workspace.folder(session.id, "workspace")
        File(work, "AGENTS.md").writeText("Use Python standard-library unittest, no new dependencies. Preserve addition. Run python3 -m unittest discover -s tests -v from /workspace. Keep code and tests in English.")
        File(work, "pyproject.toml").writeText("[project]\nname = \"mathbox\"\nversion = \"0.1.0\"\n")
        File(work, "mathbox").mkdirs(); File(work, "tests").mkdirs()
        File(work, "mathbox/core.py").writeText("def add(a, b):\n    return a + b\n")
        File(work, "mathbox/__init__.py").writeText("from .core import add\n")
        File(work, "tests/test_mathbox.py").writeText("import unittest\nfrom mathbox import add\n\nclass MathboxTest(unittest.TestCase):\n    def test_add(self):\n        self.assertEqual(add(2, 3), 5)\n")
        ins.runOnMainSync { model.sessions.add(0, session); model.persist() }
        println("Coding web fixture created: ${session.id}")
    }
}
