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
    @Test fun parentAndDelegatedRequestsShareGlobalAdmissionCapacity()=runBlocking<Unit> {
        val certificate=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCert=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCert=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val release=java.util.concurrent.CountDownLatch(1)
        val server=MockWebServer().apply {
            useHttps(serverCert.sslSocketFactory(),false)
            dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    release.await(5,TimeUnit.SECONDS)
                    return MockResponse().setBody("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}")
                }
            };start()
        }
        val root=Files.createTempDirectory("capri-model-admission").toFile()
        try {
            val client=OkHttpClient.Builder().sslSocketFactory(clientCert.sslSocketFactory(),clientCert.trustManager).build()
            val config=ModelConfig(endpoint=server.url("/v1").toString(),model="fixture",key="fixture-secret")
            val trace=PerformanceTrace(java.io.File(root,"trace.jsonl"));val entered=java.util.concurrent.atomic.AtomicInteger()
            coroutineScope {
                val requests=(1..6).map { attempt -> async {
                    entered.incrementAndGet()
                    ModelTransport(client).request(config,JSONArray().put(JSONObject().put("role","user").put("content","bounded input")),JSONArray(),128,trace,attempt,false,
                        if(attempt%2==0)ModelRequests.Purpose.DELEGATION else ModelRequests.Purpose.EXECUTION)
                } }
                try {
                    withTimeout(4000) { while(entered.get()<6 || server.requestCount<3)delay(5) }
                    delay(100);assertEquals(3,server.requestCount)
                } finally { release.countDown() }
                requests.awaitAll()
            }
            assertEquals(6,server.requestCount)
            assertEquals(6,java.io.File(root,"trace.jsonl").readLines().count { JSONObject(it).optString("stage")=="model_admission" })
        } finally { release.countDown();server.shutdown();root.deleteRecursively() }
    }
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
