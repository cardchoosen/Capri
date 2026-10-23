package ai.capri.app

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EnvironmentMaintenanceTest {
    @Test fun sharedCacheAllowlistRetainsPackagesIndexesCredentialsAndWorkspace() = runBlocking<Unit> {
        val root = Files.createTempDirectory("capri-maintenance").toFile()
        val context = object : ContextWrapper(null) { override fun getFilesDir() = root }
        val workspace = Workspace(File(root, "sessions"))
        val maintenance = EnvironmentMaintenance(context, workspace, PythonRuntime(context, workspace))
        fun seed(path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("keep") }
        try {
            val deleted = listOf("runtime/package-cache/pip/cache", "runtime/package-cache/npm/cache", "runtime/ubuntu/var/cache/apt/archives/a.deb", "runtime/ubuntu/var/cache/apt/archives/partial/download").map(::seed)
            val retained = listOf("runtime/ubuntu/var/cache/apt/archives/lock", "runtime/ubuntu/var/lib/apt/lists/index", "runtime/ubuntu/usr/bin/python3", "sessions/chat/workspace/main.cpp", "sessions/chat/home/.venv/package", "ssh/private", "sessions/other/tmp/proof").map(::seed)
            assertEquals(16L, maintenance.estimate("chat", EnvironmentMaintenance.Action.CACHE))
            maintenance.perform("chat", EnvironmentMaintenance.Action.CACHE)
            deleted.forEach { assertFalse(it.path, it.exists()) }
            retained.forEach { assertEquals(it.path, "keep", it.readText()) }
            assertEquals(0L, maintenance.estimate("chat", EnvironmentMaintenance.Action.CACHE))
            RuntimeGate.beginMaintenance(context, "chat")
            try {
                try { RuntimeGate.start(context, "chat", "脚本") { throw AssertionError("must not launch") }; fail("accepted") }
                catch (_: IllegalStateException) { }
                try { RuntimeGate.startExclusive(context, "chat") { throw AssertionError("must not launch") }; fail("accepted") }
                catch (_: IllegalStateException) { }
            } finally { RuntimeGate.endMaintenance(context) }
        } finally { RuntimeFiles.delete(root) }
    }
    @Test fun rejectsRedirectedParentsBeforeDeletingAnyTargetAndReleasesGate() = runBlocking<Unit> {
        val root = Files.createTempDirectory("capri-maintenance-link").toFile()
        val context = object : ContextWrapper(null) { override fun getFilesDir() = root }
        val workspace = Workspace(File(root, "sessions"))
        val maintenance = EnvironmentMaintenance(context, workspace, PythonRuntime(context, workspace))
        try {
            val home = workspace.folder("chat", "home")
            File(home, ".venv").mkdirs(); File(home, ".venv/keep").writeText("proof")
            Files.createSymbolicLink(File(home, ".local").toPath(), workspace.folder("chat", "workspace").toPath())
            try { maintenance.perform("chat", EnvironmentMaintenance.Action.RESET); fail("redirected") }
            catch (_: IllegalStateException) { }
            assertEquals("proof", File(home, ".venv/keep").readText())
            RuntimeGate.beginMaintenance(context, "chat"); RuntimeGate.endMaintenance(context)
        } finally { RuntimeFiles.delete(root) }
    }
}
