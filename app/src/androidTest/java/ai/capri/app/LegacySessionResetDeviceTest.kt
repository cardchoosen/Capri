package ai.capri.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LegacySessionResetDeviceTest {
    @Test fun resetRequiresValidatedBaseAndPreservesGlobalIdentityFiles() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "reset-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) { override fun getFilesDir() = root }
        fun put(path: String, content: String = "sentinel") = File(root, path).apply { parentFile!!.mkdirs(); writeText(content) }
        try {
            val old = put("sessions/old/workspace/old.txt")
            val ssh = put("runtime/ssh/identities/key")
            val core = put("persona/archive.json")
            try { LegacySessionReset.clear(isolated); fail("Reset before validation") } catch (_: IllegalStateException) { }
            assertTrue(old.isFile)
            val hash = base.assets.open("runtime/ubuntu-source.json").bufferedReader().use { JSONObject(it.readText()).getString("sha256") }
            put("runtime/ubuntu/.capri-ready", hash)
            put("runtime/ubuntu/usr/bin/python3")
            put("runtime/ubuntu/var/lib/dpkg/status")
            put("terminals.json"); put("agent-recovery/old.json"); put("runtime/tools/current/old")
            LegacySessionReset.clear(isolated)
            assertFalse(old.exists()); assertFalse(File(root,"terminals.json").exists())
            assertFalse(File(root,"runtime/tools").exists())
            assertEquals("sentinel", ssh.readText()); assertEquals("sentinel", core.readText())
            assertTrue(LinuxEnvironment(isolated).ready)
        } finally { RuntimeFiles.delete(root) }
    }
}
