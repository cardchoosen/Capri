package ai.capri.app.agent

import ai.capri.app.ModelConfig
import ai.capri.app.ModelRequests
import ai.capri.app.PerformanceTrace
import ai.capri.app.model.ModelContextRejected
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ModelTransportTest {
    @Test fun httpsInferenceUsesOneTransportAndContextRejectionDoesNotRetry() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCert = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverCert.sslSocketFactory(), false); start() }
        val root = Files.createTempDirectory("capri-transport").toFile()
        try {
            val transport = ModelTransport(OkHttpClient.Builder().sslSocketFactory(clientCert.sslSocketFactory(), clientCert.trustManager).build())
            val config = ModelConfig(endpoint = server.url("/v1").toString(), model = "fixture", key = "fixture-secret")
            val trace = PerformanceTrace(java.io.File(root, "trace.jsonl"))
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Hello"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"done"}}]}"""))
            val result = transport.request(config, messages, JSONArray(), 1024, trace, 1, true)
            assertEquals("done", result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer fixture-secret", request.getHeader("Authorization"))
            assertFalse(JSONObject(request.body.readUtf8()).has("tools"))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"context_length_exceeded","message":"maximum context length is 8192 tokens"}}"""))
            val retries = ModelRequests(pause = { fail("A context rejection must not enter transport retries") })
            try { retries.execute { transport.request(config, messages, JSONArray(), 1024, trace, retries.used, false) }; fail() }
            catch (error: ModelContextRejected) { assertEquals(8192, error.windowHint) }
            assertEquals(1, retries.used)
            assertEquals(2, server.requestCount)
            trace.context(messages, JSONArray()); trace.finish(PerformanceTrace.Status.OK)
            assertFalse(java.io.File(root, "trace.jsonl").readText().contains("fixture-secret"))
            assertFalse(java.io.File(root, "trace.jsonl").readText().contains("Hello"))
        } finally { server.shutdown(); root.deleteRecursively() }
    }
}
