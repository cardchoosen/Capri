package ai.capri.app

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EnvironmentMaintenanceDeviceTest {
    @Test fun migrationIntegrityRepairAndRecoveryPreserveUserData() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "environment-maintenance-${java.util.UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        val store = Store(isolated); val runtime = PythonRuntime(isolated, store.workspace)
        val manager = EnvironmentManager(isolated, runtime)
        val chat = "maintenance-test"
        var active: Process? = null
        val workspace = store.workspace.folder(chat, "workspace")
        val release = File(workspace, "release")
        try {
            val userFile = File(workspace, "keep.txt").apply { writeText("keep workspace") }
            val ssh = File(isolated.filesDir, "runtime/ssh/user-marker").apply { parentFile!!.mkdirs(); writeText("keep ssh") }
            val legacy = File(isolated.filesDir, "runtime/tools/" + "a".repeat(64)).apply { mkdirs() }
            File(legacy, "marker").writeText("old tool directory")
            assertTrue(runtime.install().startsWith("exit=0"))
            assertEquals("current", DevelopmentTools.prepare(isolated).name)
            assertEquals("current", NativePythonPackages.prepare(isolated).name)
            assertFalse(legacy.exists())
            assertTrue(runtime.execute(chat, "import sysconfig,pathlib;pathlib.Path(sysconfig.get_path('purelib'),'user_package.py').write_text('VALUE=42')").startsWith("exit=0"))
            val healthy = manager.inspect(chat, true)
            assertTrue(healthy.toString(), healthy.components.all { it.valid })
            assertTrue(healthy.probe, healthy.probe.startsWith("exit=0\n"))
            println("ENV_TEST: initial integrity and migration passed")

            active = runtime.startProcess(chat, store.workspace.folder(chat, "probe-run"), listOf("/usr/bin/python3", "-c",
                "import time,pathlib\nwhile not pathlib.Path('/workspace/release').exists(): time.sleep(.05)"))
            val node = File(ManagedBundle.tools.target(isolated), "usr/bin/node")
            node.writeText("damaged test executable")
            val damaged = manager.inspect(chat, true)
            assertTrue(damaged.components.first { it.id == "tools" }.detail.contains("usr/bin/node"))
            assertTrue(damaged.probe.isEmpty()) // Do not execute damaged tools during checks.
            try { manager.repair("tools"); fail("repaired under active process") } catch (_: IllegalStateException) { }
            assertEquals("damaged test executable", node.readText())
            release.writeText("release")
            assertTrue(active!!.waitFor(10, TimeUnit.SECONDS)); active = null
            manager.repair("tools")
            assertEquals(0, ManagedBundle.integrity(isolated, ManagedBundle.tools).problems)
            assertFalse(File(ManagedBundle.tools.target(isolated).parentFile!!, "current.previous").exists())
            println("ENV_TEST: corrupt bytes detected, active process blocked repair, repair passed")

            // Simulate death between old-current rename and the new-current rename.
            val science = ManagedBundle.science.target(isolated)
            val previous = File(science.parentFile, "current.previous")
            assertTrue(science.renameTo(previous))
            NativePythonPackages.prepare(isolated)
            assertTrue(science.isDirectory); assertFalse(previous.exists())
            assertEquals(0, ManagedBundle.integrity(isolated, ManagedBundle.science).problems)

            // Old managed directory may itself be a symlink: cleanup must unlink, not recurse.
            val outside = File(directory, "user-outside").apply { mkdirs() }
            File(outside, "keep.txt").writeText("outside preserved")
            val oldLink = File(ManagedBundle.tools.target(isolated).parentFile, "b".repeat(64))
            Files.createSymbolicLink(oldLink.toPath(), outside.toPath())
            DevelopmentTools.prepare(isolated)
            assertFalse(Files.isSymbolicLink(oldLink.toPath()))
            assertEquals("outside preserved", File(outside, "keep.txt").readText())

            manager.repair("base")
            assertTrue(runtime.execute(chat, "import user_package,pathlib;assert user_package.VALUE==42;assert pathlib.Path('/workspace/keep.txt').read_text()=='keep workspace';print('user state preserved')").contains("user state preserved"))
            assertEquals("keep workspace", userFile.readText()); assertEquals("keep ssh", ssh.readText())
            val repaired = manager.inspect(chat, true)
            assertTrue(repaired.toString(), repaired.components.all { it.valid })
            assertTrue(repaired.probe, repaired.probe.startsWith("exit=0\n"))
            assertTrue(ManagedBundle.legacy(ManagedBundle.tools, isolated).isEmpty())
            assertTrue(ManagedBundle.legacy(ManagedBundle.science, isolated).isEmpty())
            println("ENV_TEST: interrupted swap recovery, symlink cleanup and base repair preserve user data")
        } finally {
            release.writeText("release")
            active?.waitFor(10, TimeUnit.SECONDS)
            active?.destroyForcibly()
            ManagedBundle.deleteTree(directory)
        }
    }
}
