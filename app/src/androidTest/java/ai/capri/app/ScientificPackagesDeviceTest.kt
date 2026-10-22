package ai.capri.app

import ai.capri.app.pythonenv.PythonEnvironmentTool
import ai.capri.app.terminal.TerminalManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScientificPackagesDeviceTest {
    @Test fun offlineNativePackagesWorkPerChatWithoutResettingExistingFiles() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ai.capri.app/.MainActivity")).use { it.readBytes() }
        val store = Store(context); val runtime = PythonRuntime(context, store.workspace)
        val manager = TerminalManager.get(context); val tool = PythonEnvironmentTool(manager)
        val first = "science-package-test-a"; val second = "science-package-test-b"
        suspend fun python(chat: String, script: String): String {
            val output = runtime.execute(chat, script)
            assertTrue(output, output.startsWith("exit=0\n")); return output
        }
        suspend fun environment(chat: String, action: String, vararg packages: String): String = withTimeout(180_000) {
            val args = JSONObject().put("action", action).put("wait_ms", 1000)
            if (packages.isNotEmpty()) args.put("packages", JSONArray(packages.toList()))
            val result = JSONObject(tool.execute(chat, args)); assertEquals(result.toString(), "ok", result.getString("status"))
            val job = result.getJSONObject("data"); var offset = 0; val output = StringBuilder()
            while (true) {
                val read = JSONObject(manager.execute(chat, JSONObject().put("action", "read").put("params", JSONObject()
                    .put("terminal_id", job.getString("terminal_id")).put("command_id", job.getString("command_id"))
                    .put("offset", offset).put("wait_ms", 20000))))
                assertEquals(read.toString(), "ok", read.getString("status"))
                val data = read.getJSONObject("data"); output.append(data.getString("output")); offset = data.getInt("next_offset")
                if (data.getBoolean("complete")) {
                    assertEquals(output.toString(), 0, data.getJSONObject("command").getInt("exit_code"))
                    return@withTimeout output.toString()
                }
            }
            @Suppress("UNREACHABLE_CODE") ""
        }
        try {
            runtime.install()
            for (chat in listOf(first, second)) python(chat, """
                import sysconfig,pathlib,importlib.util
                pathlib.Path('/workspace/keep.txt').write_text('preserve-$chat')
                pathlib.Path(sysconfig.get_path('purelib'),'capri_existing_dependency.py').write_text("VALUE = '$chat'\n")
                assert importlib.util.find_spec('matplotlib') is None
                assert importlib.util.find_spec('PIL') is None
            """.trimIndent())
            val installed = environment(first, "install", "matplotlib", "numpy", "pillow")
            assertTrue(installed, installed.contains("内置Alpine官方预编译包（离线）"))
            assertFalse(installed, installed.contains("https://pypi"))
            val figures = python(first, """
                import pathlib,importlib.metadata,json,sysconfig
                import capri_existing_dependency,numpy as np,matplotlib
                from PIL import Image
                from matplotlib import pyplot as plt
                from matplotlib.animation import FuncAnimation,PillowWriter
                assert capri_existing_dependency.VALUE=='$first'
                assert pathlib.Path('/workspace/keep.txt').read_text()=='preserve-$first'
                assert '/root/.venv/' in np.__file__ and '/root/.venv/' in matplotlib.__file__
                assert matplotlib.get_backend().lower()=='agg'
                assert pathlib.Path(matplotlib.get_data_path(),'fonts/ttf/DejaVuSans.ttf').is_file()
                assert np.allclose(np.linalg.solve([[3.,1.],[1.,2.]],[9.,8.]),[2.,3.])
                fig,ax=plt.subplots(figsize=(3.2,2),dpi=100)
                x=np.arange(6);line,=ax.plot(x,x,label='Capri')
                ax.set_ylim(0,12);ax.set_title('Mature libraries');ax.legend();fig.tight_layout()
                fig.savefig('/workspace/library-check.png')
                def update(i):
                    line.set_ydata(x+i);return line,
                animation=FuncAnimation(fig,update,frames=5,blit=False)
                animation.save('/workspace/library-check.gif',writer=PillowWriter(fps=4))
                plt.close(fig)
                with Image.open('/workspace/library-check.gif') as image:
                    assert image.n_frames==5 and image.size==(320,200)
                for name in ['numpy','pillow','matplotlib']:
                    origin=json.loads(importlib.metadata.distribution(name).read_text('capri-origin.json'))
                    assert origin['source']=='Alpine v3.22 ARM64 APK'
                print('NumPy linear algebra, Pillow GIF and Matplotlib Agg plots passed')
            """.trimIndent())
            assertTrue(figures, figures.contains("plots passed"))
            val png = Attachments.inspect(store.workspace, first, "library-check.png", "native library check")
            val gif = Attachments.inspect(store.workspace, first, "library-check.gif", "native library animation")
            assertEquals("image/png", png.getString("mime"));assertEquals("image/gif", gif.getString("mime"))
            python(second, """
                import importlib.util,capri_existing_dependency,pathlib
                assert capri_existing_dependency.VALUE=='$second'
                assert importlib.util.find_spec('matplotlib') is None
                assert importlib.util.find_spec('numpy') is None
                assert importlib.util.find_spec('PIL') is None
                assert pathlib.Path('/workspace/keep.txt').read_text()=='preserve-$second'
            """.trimIndent())
            environment(second, "install", "pillow")
            python(second, "import PIL,importlib.util;assert importlib.util.find_spec('matplotlib') is None;print(PIL.__version__)")
            environment(first, "check"); environment(second, "check")
            val diagnostic = environment(first, "status")
            assertTrue(diagnostic, diagnostic.contains("musllinux_1_2_aarch64"))
            assertTrue(diagnostic, diagnostic.contains("Alpine v3.22 ARM64 APK"))
            environment(second, "uninstall", "pillow")
            python(second, "import importlib.util;assert importlib.util.find_spec('PIL') is None")
            python(first, "import PIL,numpy,matplotlib,capri_existing_dependency;print('other chat unaffected')")
            environment(first, "uninstall", "matplotlib")
            python(first, """
                import importlib.metadata,pathlib,PIL,numpy,capri_existing_dependency
                try: importlib.metadata.version('matplotlib');raise AssertionError('still installed')
                except importlib.metadata.PackageNotFoundError: pass
                assert not pathlib.Path('/root/.venv/share/matplotlib/mpl-data/matplotlibrc').exists()
                assert pathlib.Path('/workspace/library-check.gif').is_file()
                assert pathlib.Path('/workspace/keep.txt').read_text()=='preserve-$first'
            """.trimIndent())
        } finally {
            for (chat in listOf(first, second)) {
                manager.list(chat).forEach { record -> runCatching {
                    manager.execute(chat, JSONObject().put("action", "close").put("params", JSONObject().put("terminal_id", record.getString("terminal_id"))))
                } }
                store.workspace.root(chat).deleteRecursively()
            }
        }
    }
}
