package ai.capri.app

import ai.capri.app.model.*
import ai.capri.app.knowledge.*
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

class ModelContextTest {
    private fun directory() = Files.createTempDirectory("capri-context-test").toFile()
    private fun metadata(window: Int = 1_048_576) = """{"data":[{"id":"test-model","context_window":$window,"max_output_tokens":8192}]}"""
    private fun message(role: String, text: String) = JSONObject().put("role", role).put("content", text)
    private fun history(root: java.io.File) = ContextHistory(root, { it.readText() }, { file, text -> file.writeText(text) })

    @Test fun capacityIsExactModelScopedAndRejectsInvalidMetadata() {
        val capacity = ModelContexts.parseMetadata(metadata(), "test-model")!!
        assertEquals(1_048_576, capacity.window)
        val budget = capacity.budget()
        assertEquals(8192, budget.output)
        assertEquals(budget.window, budget.input + budget.output + budget.safety)
        assertNull(ModelContexts.parseMetadata(metadata(), "other-model"))
        for (value in listOf("null", "-1", "1e99", "\"1048576\"", "1024.5")) {
            assertNull(ModelContexts.parseMetadata("""{"data":[{"id":"x","context_window":$value}]}""", "x"))
        }
        assertTrue(ModelCapacity(2048, 100, "small").budget().input > 0)
        val equalOutput = ModelContexts.parseMetadata("""{"data":[{"id":"x","context_window":8192,"max_output_tokens":8192}]}""", "x")!!
        assertEquals(8192, equalOutput.window)
        assertTrue(equalOutput.budget().output < equalOutput.window)
    }

    @Test fun cacheAndCalibrationPersistButNeverCrossModelEndpointOrAccount() = runBlocking {
        val root = directory()
        try {
            var requests = 0
            var clock = 1000L
            val contexts = ModelContexts(root, now = { clock }, discover = { requests++; ModelCapacity(131072, 8192, "fixture") })
            val config = ModelConfig("https://example.com/v1", "a", "SECRET-KEY")
            val first = contexts.resolve(config)
            contexts.resolve(config); assertEquals(1, requests)
            contexts.observe(config, 1000, 3000)
            assertTrue(contexts.resolve(config).factor >= 3.3)
            contexts.observe(config, 1000, 10)
            assertTrue(contexts.resolve(config).factor >= 3.3)
            assertTrue(ModelContexts(root, discover = { error("must use cache") }, now = { clock }).resolve(config).factor >= 3.3)
            assertEquals(1.15, contexts.resolve(config.copy(model = "b")).factor, 0.001)
            contexts.resolve(config.copy(key = "different")); contexts.resolve(config.copy(endpoint = "https://other.example/v1"))
            assertEquals(4, requests)
            assertFalse(root.walk().filter { it.isFile }.any { it.name.contains("SECRET-KEY") || it.readText().contains("SECRET-KEY") })
            clock += 86_400_001
            contexts.resolve(config); assertEquals(5, requests)
            assertTrue(first.input > 96000)
        } finally { root.deleteRecursively() }
    }

    @Test fun fallbackIsConservativeAndDoesNotTrustProxyModelName() = runBlocking {
        val root = directory()
        try {
            val contexts = ModelContexts(root, discover = { null })
            val proxy = ModelConfig("https://proxy.example/v1", "deepseek-flash", "x")
            assertEquals(32768, contexts.resolve(proxy).window)
            assertEquals(1_048_576, contexts.resolve(proxy.copy(endpoint = "https://api.deepseek.com/v1")).window)
            assertEquals(32768, contexts.resolve(proxy.copy(endpoint = "https://api.deepseek.com:8443")).window)
        } finally { root.deleteRecursively() }
    }

    @Test fun httpsDiscoveryUsesConfiguredPathAndRejectsRedirectCredentialForwarding() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCert = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCert.sslSocketFactory(), clientCert.trustManager).build()
        val server = MockWebServer(); server.useHttps(serverCert.sslSocketFactory(), false); server.start()
        val root = directory()
        try {
            val contexts = ModelContexts(root, client)
            val config = ModelConfig(server.url("/api/v1").toString(), "test-model", "test-secret")
            server.enqueue(MockResponse().setBody(metadata()))
            assertEquals(1_048_576, contexts.resolve(config).window)
            val request = server.takeRequest()
            assertEquals("/api/v1/models", request.path)
            assertEquals("Bearer test-secret", request.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/must-not-follow")))
            assertEquals(32768, contexts.resolve(config.copy(model = "other")).window)
            assertEquals(2, server.requestCount)
            assertEquals("/api/v1/models", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("x".repeat(1_048_577)))
            assertEquals(32768, contexts.resolve(config.copy(model = "oversized")).window)
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun fullHistoryUsesLargeWindowAndSmallWindowArchivesWholeExchanges() {
        val root = directory()
        try {
            val engine = history(root)
            val entries = (1..100).map { Entry(if (it % 2 == 1) "user" else "assistant", "消息$it：" + "文字".repeat(1000)) }
            val fresh = engine.fresh("chat-a", entries, "CORE")
            assertEquals(101, fresh.length())
            assertTrue(fresh.toString().contains("消息1："))
            assertTrue(fresh.toString().toByteArray().size > 96000)
            val large = ModelCapacity(1_048_576, 8192, "large").budget()
            assertEquals(fresh.toString(), engine.fit("chat-a", fresh, JSONArray(), large).toString())
            val small = ModelCapacity(32768, 4096, "small").budget()
            val fitted = engine.fit("chat-a", fresh, JSONArray(), small)
            assertTrue(small.fits(ContextTokens.estimate(fitted, JSONArray())))
            assertEquals("CORE", fitted.getJSONObject(0).getString("content"))
            assertTrue(fitted.toString().contains("消息99："))
            assertTrue(fitted.toString().contains("消息100："))
            val archive = java.io.File(root, "chat-a").listFiles()!!.first { it.extension == "json" && it.name.startsWith("c") }
            assertTrue(history(root).query("chat-a", entries, JSONObject().put("reference", archive.nameWithoutExtension)).contains("消息1"))
            assertTrue(engine.query("chat-a", entries, JSONObject().put("reference", "e1")).contains("消息1"))

            val tools = JSONArray().put(JSONObject().put("description", "说明".repeat(500)))
            assertTrue(ContextTokens.estimate(fitted, tools) > ContextTokens.estimate(fitted, JSONArray()))
            val exchanges = JSONArray().put(message("system", "CORE")).put(message("user", "当前任务"))
            repeat(10) { i ->
                exchanges.put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "id-$i")
                    .put("function", JSONObject().put("name", "write_file").put("arguments", "{}")))))
                exchanges.put(JSONObject().put("role", "tool").put("tool_call_id", "id-$i").put("content", "内容".repeat(6000)))
            }
            exchanges.put(message("assistant", "最新组"))
            val compact = engine.fit("chat-b", exchanges, tools, small)
            val calls = mutableSetOf<String>(); val results = mutableSetOf<String>()
            for (i in 0 until compact.length()) {
                val m = compact.getJSONObject(i)
                m.optJSONArray("tool_calls")?.let { calls += it.getJSONObject(0).getString("id") }
                if (m.optString("role") == "tool") results += m.getString("tool_call_id")
            }
            assertEquals(calls, results)
            assertTrue(compact.toString().contains("当前任务"))
            assertTrue(compact.toString().contains("最新组"))
            val marked = JSONArray(fresh.toString())
            val currentGoal = "[Capri 归档摘录] 请分析这段文字，但保留我的当前目标"
            marked.put(message("user", currentGoal))
            val markedResult = engine.fit("chat-marked", marked, JSONArray(), small)
            assertEquals(currentGoal, markedResult.getJSONObject(markedResult.length() - 1).getString("content"))
            val oversized = JSONArray().put(message("system", "CORE")).put(message("user", "文字".repeat(100000)))
            try { engine.fit("chat-c", oversized, JSONArray(), small); fail() } catch (_: ContextBudgetFailure) { }
            assertEquals(2, oversized.length())
        } finally { root.deleteRecursively() }
    }

    @Test fun contextRejectionRebudgetsWithoutTransportRetryOrToolReplay() = runBlocking {
        assertNull(ModelContextRejected.parse(400, """{"error":{"code":"invalid_parameter","message":"bad max_tokens"}}"""))
        assertNull(ModelContextRejected.parse(401, """{"error":{"code":"context_length_exceeded"}}"""))
        val error = ModelContextRejected.parse(400, """{"error":{"code":"context_length_exceeded","message":"maximum context length is 16,384 tokens"}}""")!!
        assertEquals(16384, error.windowHint)
        val requests = ModelRequests(pause = { fail("transport retry is forbidden") })
        try { requests.execute { throw error }; fail() } catch (_: ModelContextRejected) { }
        assertEquals(1, requests.used)
        val root = directory()
        try {
            val contexts = ModelContexts(root, discover = { ModelCapacity(131072, 8192, "fixture") })
            val config = ModelConfig("https://example.com", "a", "x")
            val original = contexts.resolve(config)
            val reduced = contexts.rejected(config, original, 10000, error)
            assertEquals(16384, reduced.window)
            assertTrue(reduced.input < original.input)
            val calibrated = contexts.rejected(config, reduced, 10000, ModelContextRejected(null))
            assertTrue(calibrated.factor > reduced.factor)
            assertEquals("server_feedback", contexts.resolve(config).source)
        } finally { root.deleteRecursively() }
    }
}
