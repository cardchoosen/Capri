package ai.capri.app

import ai.capri.app.security.CertificateTool
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CertificateDeviceTest {
    @Test fun offlineInspectRealAndroidStoreAndBuiltinSkill() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "certificate-test-${java.util.UUID.randomUUID()}")
        try {
            val workspace = Workspace(dir)
            val rules = JSONObject(context.assets.open("security/emudhra-2026.json").bufferedReader().use { it.readText() })
            val tool = CertificateTool(workspace, rules)
            workspace.file("test", "public.pem").writeText(HeldCertificate.Builder().commonName("Local fixture").build().certificatePem())
            val inspected = JSONObject(tool.execute("test", JSONObject().put("action", "inspect").put("path", "public.pem")))
            assertEquals("none", inspected.getJSONArray("certificates").getJSONObject(0).getJSONObject("incident").getString("match"))
            for (name in listOf("EM-DV-TLS-CA-G3B", "EM-DV-TLS-CA-G3B-AAA")) {
                workspace.file("test", "known.pem").writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("certificates/$name.crt.pem").use { it.readBytes() })
                val known = JSONObject(tool.execute("test", JSONObject().put("action", "inspect").put("path", "known.pem")))
                assertTrue(known.getJSONArray("certificates").getJSONObject(0).getJSONObject("incident").getBoolean("known_compromised_key"))
            }
            val store = JSONObject(tool.execute("test", JSONObject().put("action", "trust_store")))
            assertEquals("AndroidCAStore", store.getString("store"))
            assertTrue(store.getInt("total_certificates") > 0)
            assertFalse(store.getJSONObject("boundaries").getBoolean("trust_store_modified"))
            assertTrue(context.assets.open("extensions/skills/certificate-security/SKILL.md").bufferedReader().use { it.readText() }.contains("certificate-security"))
        } finally { dir.deleteRecursively() }
    }
}
