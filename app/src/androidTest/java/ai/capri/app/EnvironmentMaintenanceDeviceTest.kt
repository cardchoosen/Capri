package ai.capri.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EnvironmentMaintenanceDeviceTest {
    @Test fun cleanupPreservesWorkspaceAndResetRebuildsOnlyManagedDependencies() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context); val runtime = PythonRuntime(context, store.workspace)
        val maintenance = EnvironmentMaintenance(context, store.workspace, runtime)
        val chat = "maintenance-${java.util.UUID.randomUUID()}"
        val other = "maintenance-${java.util.UUID.randomUUID()}"
        try {
            runtime.ensureSession(chat)
            store.workspace.file(chat, "keep.txt").writeText("workspace")
            store.workspace.folder(chat, "workspace/node_modules").resolve("keep").writeText("project")
            store.workspace.folder(other, "home/.venv").resolve("keep").writeText("other")
            val home = store.workspace.folder(chat, "home")
            File(home, "keep").writeText("home")
            File(home, ".venv/remove-proof").writeText("dependency")
            store.workspace.folder(chat, "home/.local/npm").resolve("remove-proof").writeText("npm")
            store.workspace.folder(chat, "tmp").resolve("remove").writeText("temporary")
            // A child symlink must be unlinked, never traversed into workspace.
            Files.createSymbolicLink(File(store.workspace.folder(chat, "tmp"), "link").toPath(), store.workspace.folder(chat, "workspace").toPath())
            val run = store.workspace.folder(chat, "runs/probe")
            val live = runtime.startProcess(chat, run, listOf("/bin/sleep", "30"))
            try {
                try { maintenance.perform(chat, EnvironmentMaintenance.Action.TEMP); fail("accepted live process") }
                catch (_: IllegalStateException) { }
                assertTrue(store.workspace.folder(chat, "tmp").resolve("remove").exists())
            } finally { OwnedProcesses.stop(live) }
            maintenance.perform(chat, EnvironmentMaintenance.Action.TEMP)
            assertFalse(File(store.workspace.root(chat), "tmp").exists())
            assertEquals("workspace", store.workspace.file(chat, "keep.txt").readText())
            maintenance.perform(chat, EnvironmentMaintenance.Action.RESET)
            assertFalse(File(home, ".venv/remove-proof").exists())
            assertFalse(File(home, ".local/npm").exists())
            assertTrue(File(home, ".capri-session-ready").exists())
            assertEquals("home", File(home, "keep").readText())
            assertEquals("other", store.workspace.folder(other, "home/.venv").resolve("keep").readText())
            assertTrue(store.workspace.file(chat, "node_modules/keep").exists())
            assertTrue(runtime.execute(chat, "import pip,sys;print('reset-ready')").contains("reset-ready"))
            // A redirected managed parent must abort before deleting the venv.
            RuntimeFiles.delete(File(home, ".local"))
            Files.createSymbolicLink(File(home, ".local").toPath(), store.workspace.folder(chat, "workspace").toPath())
            try { maintenance.perform(chat, EnvironmentMaintenance.Action.RESET); fail("accepted redirected parent") }
            catch (_: IllegalStateException) { }
            assertTrue(File(home, ".venv/pyvenv.cfg").exists())
            assertEquals("workspace", store.workspace.file(chat, "keep.txt").readText())
        } finally { RuntimeFiles.delete(store.workspace.root(chat)); RuntimeFiles.delete(store.workspace.root(other)) }
    }
}
