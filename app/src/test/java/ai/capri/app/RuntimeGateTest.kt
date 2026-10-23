package ai.capri.app

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.concurrent.*

class RuntimeGateTest {
    private class FakeProcess : Process() {
        @Volatile var alive = true
        override fun isAlive() = alive
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { alive = false; return 0 }
        override fun exitValue(): Int { if (alive) throw IllegalThreadStateException(); return 0 }
        override fun destroy() { alive = false }
    }
    @Test fun activeRuntimeBlocksMutationUntilProcessEnds() {
        val folder = Files.createTempDirectory("capri-gate").toFile()
        val context = object : ContextWrapper(null) { override fun getFilesDir() = folder }
        val process = FakeProcess()
        try {
            RuntimeGate.start(context, "chat", "terminal") { process }
            try { RuntimeGate.mutate(context) { fail("mutation entered") }; fail("accepted live process") }
            catch (_: IllegalStateException) { }
            process.alive = false
            assertEquals("idle", RuntimeGate.mutate(context) { "idle" })
        } finally { process.alive = false; RuntimeGate.active(context); folder.deleteRecursively() }
    }
    @Test fun newProcessCannotStartHalfwayThroughMaintenance() {
        val folder = Files.createTempDirectory("capri-gate-race").toFile()
        val context = object : ContextWrapper(null) { override fun getFilesDir() = folder }
        val process = FakeProcess(); val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val requested = CountDownLatch(1)
        try {
            val maintenance = executor.submit { RuntimeGate.mutate(context) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val starting = executor.submit<Process> { requested.countDown(); RuntimeGate.start(context, "chat", "script") { process } }
            assertTrue(requested.await(1, TimeUnit.SECONDS))
            try { starting.get(100, TimeUnit.MILLISECONDS); fail("process started during maintenance") }
            catch (_: TimeoutException) { }
            release.countDown(); maintenance.get(2, TimeUnit.SECONDS)
            assertSame(process, starting.get(2, TimeUnit.SECONDS))
        } finally { release.countDown(); process.alive = false; executor.shutdownNow(); RuntimeGate.active(context); folder.deleteRecursively() }
    }
    @Test fun componentCleanupDoesNotFollowSymlinksIntoUserFiles() {
        val root = Files.createTempDirectory("capri-cleanup").toFile()
        try {
            val outside = File(root, "user").apply { mkdirs() }
            File(outside, "keep").writeText("retain")
            val component = File(root, "component").apply { mkdirs() }
            File(component, "data").writeText("abc")
            Files.createSymbolicLink(File(component, "link").toPath(), outside.toPath())
            assertEquals(3L, ManagedBundle.size(component))
            ManagedBundle.deleteTree(component)
            assertEquals("retain", File(outside, "keep").readText())
        } finally { root.deleteRecursively() }
    }
}
