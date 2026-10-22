package ai.capri.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.capri.app.pythonenv.PythonEnvironmentTool
import ai.capri.app.terminal.TerminalManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonEnvironmentDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = TerminalManager.get(context)
    private suspend fun terminal(chat: String, action: String, params: JSONObject): JSONObject {
        val raw = JSONObject(manager.execute(chat, JSONObject().put("action", action).put("params", params)))
        assertEquals(raw.toString(), "ok", raw.getString("status"))
        return raw.getJSONObject("data")
    }
    private suspend fun completed(chat: String, result: String): String = withTimeout(180_000) {
        val response = JSONObject(result)
        assertEquals(response.toString(), "ok", response.getString("status"))
        val job = response.getJSONObject("data")
        var offset = 0
        val output = StringBuilder()
        while (true) {
            val data = terminal(chat, "read", JSONObject().put("terminal_id", job.getString("terminal_id"))
                .put("command_id", job.getString("command_id")).put("offset", offset).put("wait_ms", 20000))
            output.append(data.getString("output")); offset = data.getInt("next_offset")
            if (data.getBoolean("complete")) {
                assertEquals(output.toString(), "completed", data.getJSONObject("command").getString("state"))
                break
            }
        }
        output.toString()
    }
    @Test fun packagesAreSharedBySessionPythonAndTerminalButDifferentAcrossChats() = runBlocking<Unit> {
        val store = Store(context)
        val runtime = PythonRuntime(context, store.workspace)
        val tool = PythonEnvironmentTool(manager)
        val first = "python-env-test-first"
        val second = "python-env-test-second"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        runtime.install()
        try {
            // Emulate an older venv without pip, while preserving a user's existing module and workspace.
            val legacy = runtime.execute(first, """
                import sys,sysconfig,pathlib,shutil
                site=pathlib.Path(sysconfig.get_path('purelib'))
                (site/'capri_preserved.py').write_text('VALUE=42\n')
                pathlib.Path('keep.txt').write_text('user data')
                shutil.rmtree(site/'pip')
                for metadata in site.glob('pip-*.dist-info'): shutil.rmtree(metadata)
                print('legacy-ready')
            """.trimIndent())
            assertTrue(legacy, legacy.contains("exit=0"))
            val status = JSONObject(completed(first, tool.execute(first, JSONObject().put("action", "status"))))
            assertTrue(status.toString(), status.getBoolean("in_venv"))
            assertEquals("/root/.venv", status.getString("prefix"))
            assertTrue(status.getString("pip").isNotBlank())
            val interrupted = runtime.execute(first, """
                import pathlib,sysconfig,shutil
                site=pathlib.Path(sysconfig.get_path('purelib'))
                shutil.rmtree(site/'pip')
                for metadata in site.glob('pip-*.dist-info'): shutil.rmtree(metadata)
                (site/'pip').mkdir()
                (site/'pip/__init__.py').write_text('# incomplete seed')
                pathlib.Path('/root/.capri-pip-seeding').touch()
                print('interrupted-seed-prepared')
            """.trimIndent())
            assertTrue(interrupted, interrupted.contains("exit=0"))
            val repaired = runtime.execute(first, "import pip._internal.cli.main,capri_preserved,pathlib;assert capri_preserved.VALUE==42;assert not pathlib.Path('/root/.capri-pip-seeding').exists();print('seed-recovered')")
            assertTrue(repaired, repaired.contains("exit=0"))
            for ((chat, version) in listOf(first to "24.2", second to "25.0")) {
                completed(chat, tool.execute(chat, JSONObject().put("action", "install").put("packages", JSONArray(listOf("packaging==$version")))))
                val imported = runtime.execute(chat, "import packaging,sys;print(packaging.__version__);print(sys.prefix);assert packaging.__version__=='$version';assert sys.prefix=='/root/.venv'")
                assertTrue(imported, imported.contains("exit=0"))
                val record = manager.list(chat).last { it.optString("title") == "Python 包管理" }
                val command = terminal(chat, "run", JSONObject().put("terminal_id", record.getString("terminal_id"))
                    .put("command", "python3 -c 'import packaging,sys;print(packaging.__version__);assert packaging.__version__==\"$version\";assert sys.prefix==\"/root/.venv\"'").put("wait_ms", 3000))
                assertTrue(completed(chat, JSONObject().put("status", "ok").put("data", command).toString()).contains(version))
            }
            val listed = JSONArray(completed(first, tool.execute(first, JSONObject().put("action", "list"))))
            assertTrue((0 until listed.length()).any { listed.getJSONObject(it).getString("name") == "packaging" && listed.getJSONObject(it).getString("version") == "24.2" })
            assertTrue(completed(first, tool.execute(first, JSONObject().put("action", "freeze"))).contains("packaging==24.2"))
            assertTrue(completed(first, tool.execute(first, JSONObject().put("action", "check"))).contains("No broken requirements found"))
            completed(first, tool.execute(first, JSONObject().put("action", "uninstall").put("packages", JSONArray(listOf("packaging")))))
            val removed = runtime.execute(first, "import importlib.util,capri_preserved,pathlib;assert importlib.util.find_spec('packaging') is None;assert capri_preserved.VALUE==42;assert pathlib.Path('keep.txt').read_text()=='user data';print('preserved')")
            assertTrue(removed, removed.contains("exit=0"))
            val other = runtime.execute(second, "import packaging;assert packaging.__version__=='25.0';print('other-session-kept')")
            assertTrue(other, other.contains("exit=0"))
            val bad = JSONObject(tool.execute(first, JSONObject().put("action", "install").put("packages", JSONArray(listOf("-r keep.txt")))))
            assertEquals("invalid_argument", bad.getString("status"))
        } finally {
            for (chat in listOf(first, second)) {
                manager.list(chat).forEach { record ->
                    val params = JSONObject().put("terminal_id", record.getString("terminal_id"))
                    terminal(chat, "close", params)
                    terminal(chat, "delete", params)
                }
                store.workspace.root(chat).deleteRecursively()
            }
        }
    }
}
