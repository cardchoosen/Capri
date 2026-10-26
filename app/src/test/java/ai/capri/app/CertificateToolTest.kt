package ai.capri.app

import ai.capri.app.security.CertificateTool
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CertificateToolTest {
    private val rules get() = JSONObject(File("src/main/assets/security/emudhra-2026.json").readText())
    private fun fixture(name: String) = javaClass.getResourceAsStream("/certificates/$name.crt.pem")!!.use { it.readBytes() }
    private fun withTool(block: (Workspace, CertificateTool) -> Unit) {
        val dir = Files.createTempDirectory("cert-tool").toFile()
        try { val w = Workspace(dir); block(w, CertificateTool(w, rules, { emptyList() })) }
        finally { dir.deleteRecursively() }
    }
    @Test fun publicIncidentCorpusMatchesAllCertificatesIncludingCrossSigns() = withTool { w, tool ->
        val records = rules.getJSONArray("certificates")
        assertEquals(16, records.length())
        val keys = mutableSetOf<String>()
        for (i in 0 until records.length()) {
            val r = records.getJSONObject(i)
            val f = w.file("chat", "certificate.pem"); f.writeBytes(fixture(r.getString("label")))
            val output = JSONObject(tool.execute("chat", JSONObject().put("action", "inspect").put("path", "/workspace/certificate.pem")))
            val cert = output.getJSONArray("certificates").getJSONObject(0)
            assertEquals(r.getString("certificate_sha256"), cert.getString("certificate_sha256"))
            assertEquals(r.getString("spki_sha256"), cert.getString("spki_sha256"))
            assertTrue(cert.getJSONObject("incident").getBoolean("known_compromised_key"))
            assertEquals("not_checked", output.getJSONObject("boundaries").getString("revocation_status"))
            keys += cert.getString("spki_sha256")
        }
        assertEquals(8, keys.size)
    }
    @Test fun reissuedSameKeyMatchesSpkiAndSameNameDoesNotProveCompromise() = withTool { w, _ ->
        val first = HeldCertificate.Builder().commonName("eMudhra test only").certificateAuthority(1).build()
        val reissue = HeldCertificate.Builder().commonName("renamed").keyPair(first.keyPair).serialNumber(42).build()
        val records = org.json.JSONArray().put(JSONObject().put("certificate_sha256", CertificateTool.sha256(first.certificate.encoded))
            .put("spki_sha256", CertificateTool.sha256(first.keyPair.public.encoded)).put("label", "test-only"))
        val tool = CertificateTool(w, rules.put("certificates", records), { listOf(first.certificate) })
        w.file("chat", "test.pem").writeText(reissue.certificatePem())
        val result = JSONObject(tool.execute("chat", JSONObject().put("action", "inspect").put("path", "test.pem")))
        assertEquals("spki_sha256", result.getJSONArray("certificates").getJSONObject(0).getJSONObject("incident").getString("match"))
        val different = HeldCertificate.Builder().commonName("eMudhra test only").build()
        w.file("chat", "test.pem").writeText(different.certificatePem())
        val unknown = JSONObject(tool.execute("chat", JSONObject().put("action", "inspect").put("path", "test.pem")))
            .getJSONArray("certificates").getJSONObject(0)
        assertTrue(unknown.getBoolean("related_name_hint"))
        assertEquals("none", unknown.getJSONObject("incident").getString("match"))
    }
    @Test fun verifiesUnorderedIssuerLinksButDoesNotClaimFullValidation() = withTool { w, tool ->
        val issuer = HeldCertificate.Builder().commonName("Issuer").certificateAuthority(1).build()
        val leaf = HeldCertificate.Builder().commonName("Leaf").signedBy(issuer).build()
        w.file("chat", "chain.pem").writeText(issuer.certificatePem() + leaf.certificatePem())
        val result = JSONObject(tool.execute("chat", JSONObject().put("action", "inspect").put("path", "chain.pem")))
        val link = result.getJSONArray("issuer_links").getJSONObject(0)
        assertEquals(1, link.getInt("child_index")); assertEquals(0, link.getInt("issuer_index")); assertTrue(link.getBoolean("signature_valid"))
        assertEquals("not_performed", result.getJSONObject("boundaries").getString("pkix_validation"))
        val impostor = HeldCertificate.Builder().commonName("Issuer").certificateAuthority(1).build()
        w.file("chat", "chain.pem").writeText(impostor.certificatePem() + leaf.certificatePem())
        assertFalse(JSONObject(tool.execute("chat", JSONObject().put("action", "inspect").put("path", "chain.pem")))
            .getJSONArray("issuer_links").getJSONObject(0).getBoolean("signature_valid"))
    }
    @Test fun rejectsPrivateKeysMixedPayloadsOversizeAndWorkspaceEscapes() = withTool { w, tool ->
        val cert = HeldCertificate.Builder().commonName("test").build()
        val pem = cert.certificatePem()
        for (data in listOf(cert.privateKeyPkcs8Pem(), pem + cert.privateKeyPkcs8Pem(), pem + "trailing", pem.repeat(33), "not a certificate", "x".repeat(CertificateTool.MAX_BYTES + 1))) {
            w.file("chat", "input").writeText(data)
            assertTrue(runCatching { tool.execute("chat", JSONObject().put("action", "inspect").put("path", "input")) }.isFailure)
        }
        for (path in listOf("../outside", "/etc/passwd", "../../other/workspace/key")) {
            assertTrue(runCatching { tool.execute("chat", JSONObject().put("action", "inspect").put("path", path)) }.isFailure)
        }
        assertTrue(runCatching { tool.execute("chat", JSONObject().put("action", "trust_store").put("path", "input")) }.isFailure)
        assertTrue(runCatching { tool.execute("chat", JSONObject().put("action", "incident").put("unknown", true)) }.isFailure)
        w.file("chat", "input").writeBytes(cert.certificate.encoded)
        assertEquals(1, JSONObject(tool.execute("chat", JSONObject().put("action", "inspect").put("path", "input"))).getJSONArray("certificates").length())
    }
    @Test fun trustStoreReportsObservedAnchorsWithoutClaimingClientProtection() = withTool { w, _ ->
        val cert = CertificateTool.parse(fixture("EM-DV-TLS-CA-G3B")).single()
        val result = JSONObject(CertificateTool(w, rules, { listOf(cert) }).execute("chat", JSONObject().put("action", "trust_store")))
        assertEquals(1, result.getInt("related_count"))
        assertFalse(result.getJSONObject("boundaries").getBoolean("trust_store_modified"))
        assertFalse(result.has("device_safe"))
        assertTrue(runCatching { CertificateTool(w, rules, { error("store unavailable") }).execute("chat", JSONObject().put("action", "trust_store")) }.isFailure)
    }
}
