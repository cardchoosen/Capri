package ai.capri.app

import ai.capri.app.retrieval.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebRetrievalDeviceTest {
    private class FixtureHttp : RetrievalHttp {
        var apiCalls = 0; var foreignFails = false; var apiCode = 200; var delayed = false
        val gets = mutableListOf<String>()
        override suspend fun get(raw: String, head: Boolean): HttpPage {
            gets += (if (head) "HEAD " else "GET ") + raw
            if (delayed) delay(60000)
            if (head) return HttpPage(raw, if (raw.contains("duckduckgo")) 200 else 503, "text/html", byteArrayOf())
            if (raw.contains("duckduckgo")) {
                if (foreignFails) throw java.io.IOException("fixture transport error")
                return HttpPage(raw, 200, "text/html", "<div class=result><a class=result__a href='https://example.com/guide'>Guide</a><div class=result__snippet>Source snippet only</div></div>".toByteArray())
            }
            if (raw.contains("bing.com")) return HttpPage(raw, 200, "text/html", "<li class=b_algo><h2><a href='https://example.com/guide'>国内搜索结果</a></h2><div class=b_caption><p>国内摘要</p></div></li>".toByteArray())
            return HttpPage(raw, 200, "text/html", ("<title>Guide</title><main><h1>正文指南</h1><p>" + "正文内容。".repeat(2200) + "</p><pre>    exact_indent()</pre></main>").toByteArray())
        }
        override suspend fun api(url: String, body: String, key: String): HttpPage {
            apiCalls++; assertEquals("fixture-key", key)
            return HttpPage(url, apiCode, "application/json", if (url.endsWith("search")) "{\"results\":[{\"title\":\"Provider\",\"url\":\"https://example.com/provider\",\"summary\":\"Provider snippet\"}]}".toByteArray() else "{\"title\":\"Provider page\",\"content\":\"Provider body\"}".toByteArray())
        }
    }
    @Test fun nativeHttpBoundsRedirectsCredentialsAndCancellation() = runBlocking {
        val certificate = okhttp3.tls.HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCert = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = okhttp3.mockwebserver.MockWebServer().apply { useHttps(serverCert.sslSocketFactory(), false); start() }
        val http = PublicHttp(true, okhttp3.OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) })
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/html").setBody("<main>public body</main>"))
            assertTrue(http.get("https://example.com/page").text().contains("public body"))
            assertNull(server.takeRequest().getHeader("Authorization"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("x").setHeader("Content-Length", "3000001"))
            val tooLarge = runCatching { http.get("https://example.com/large") }.exceptionOrNull()
            assertEquals("body_too_large", (tooLarge as RetrievalFailure).category); server.takeRequest()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", "https://127.0.0.1/private"))
            val privateRedirect = runCatching { http.get("https://example.com/redirect") }.exceptionOrNull()
            assertEquals("non_public_url", (privateRedirect as RetrievalFailure).category); server.takeRequest()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", "https://other.example/steal"))
            assertEquals(302, http.api("https://api.deepseek.com/v1/tools/search", "{\"query\":\"fixture\"}", "fixture-key").status)
            assertEquals("Bearer fixture-key", server.takeRequest().getHeader("Authorization"))
            val count = server.requestCount
            assertTrue(runCatching { http.api("https://proxy.example.com/v1/tools/search", "{}", "fixture-key") }.isFailure)
            assertEquals(count, server.requestCount)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val task = launch { http.get("https://example.com/cancel") }
            delay(100); withTimeout(1500) { task.cancelAndJoin() }; assertTrue(task.isCancelled)
        } finally { server.shutdown() }
    }
    @Test fun routesCachesScopesRestartsAndCancellation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "retrieval-test-${UUID.randomUUID()}"); val http = FixtureHttp(); var net = "network-a"
        val config = ModelConfig("https://api.deepseek.com", "deepseek-flash", "fixture-key")
        val store = RetrievalStore(root)
        val web = WebRetrieval(store, http, { config }, { false }, {}, { net })
        try {
            val query = JSONObject().put("query", "official guide")
            val initial = JSONObject(web.search("chat-a", query)); assertEquals("international", initial.getString("route")); assertEquals(0, http.apiCalls)
            val before = http.gets.size
            assertTrue(JSONObject(web.search("chat-a", query)).getBoolean("cached")); assertEquals(before, http.gets.size)
            http.foreignFails = true; net = "network-b"
            val fallback = JSONObject(web.search("chat-a", JSONObject().put("query", "guide after network change")))
            assertEquals("domestic", fallback.getString("route")); assertEquals("bing-cn", fallback.getString("provider"))
            assertTrue(fallback.getJSONArray("attempts").toString().contains("network_error"))
            val id = fallback.getJSONArray("results").getJSONObject(0).getString("source_id")
            assertEquals("search_snippet", store.get("chat-a", id).getString("kind"))
            assertTrue(runCatching { web.read("chat-b", JSONObject().put("source_id", id)) }.isFailure)
            val body = JSONObject(web.read("chat-a", JSONObject().put("source_id", id)))
            assertTrue(body.getBoolean("has_more")); assertEquals("web_page", body.getString("kind")); assertTrue(body.getString("text").contains("正文指南"))
            val restarted = WebRetrieval(RetrievalStore(root), http, { config }, { false }, {}, { net })
            val readsBefore = http.gets.size
            val next = JSONObject(restarted.read("chat-a", JSONObject().put("source_id", body.getString("source_id")).put("offset", body.getInt("next_offset"))))
            assertEquals(readsBefore, http.gets.size); assertTrue(next.getString("text").contains("exact_indent()"))
            assertEquals(body.getString("url"), next.getString("url")); assertEquals(body.getLong("fetched_at"), next.getLong("fetched_at"))
            assertTrue(runCatching { web.read("chat-a", JSONObject().put("url", "http://127.0.0.1")) }.isFailure)
            assertTrue(runCatching { web.search("chat-a", JSONObject().put("query", "x").put("count", 99)) }.isFailure)
            for (invalid in listOf<Number>(1.5, 4294967297L)) {
                assertTrue(runCatching { web.search("chat-a", JSONObject().put("query", "x").put("count", invalid)) }.isFailure)
                assertTrue(runCatching { web.read("chat-a", JSONObject().put("source_id", body.getString("source_id")).put("offset", invalid)) }.isFailure)
            }
            assertTrue(runCatching { web.read("chat-a", JSONObject().put("url", "https://example.com").put("source_id", id)) }.isFailure)
            http.delayed = true
            val job = launch { web.read("chat-a", JSONObject().put("url", "https://example.com/cancel")) }
            delay(50); job.cancelAndJoin(); assertTrue(job.isCancelled)
        } finally { root.deleteRecursively() }
    }
    @Test fun enhancementsAreExplicitProviderScopedAndFailureFallsBack() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "enhanced-test-${UUID.randomUUID()}"); val http = FixtureHttp()
        var enabled = false; var config = ModelConfig("https://api.deepseek.com", "any-name", "fixture-key"); var status = ""
        val web = WebRetrieval(RetrievalStore(root), http, { config }, { enabled }, { status = it }, { "net" })
        fun query(s: String) = JSONObject().put("query", s).put("refresh", true)
        try {
            web.search("chat", query("disabled")); assertEquals(0, http.apiCalls)
            enabled = true; config = config.copy(endpoint = "https://proxy.example.com/v1", model = "deepseek")
            web.search("chat", query("proxy")); assertEquals(0, http.apiCalls)
            config = config.copy(endpoint = "https://api.deepseek.com")
            val result = JSONObject(web.search("chat", query("enhanced"))); assertEquals("deepseek", result.getString("provider")); assertEquals(1, http.apiCalls)
            assertEquals("最近一次搜索请求成功", status)
            http.apiCode = 402
            val fallback = JSONObject(web.search("chat", query("account failure"))); assertEquals("ok", fallback.getString("status")); assertTrue(fallback.getJSONArray("attempts").toString().contains("provider_http_402"))
            val calls = http.apiCalls
            web.search("chat", query("next request uses backoff")); assertEquals(calls, http.apiCalls)
            enabled = false
            web.read("chat", JSONObject().put("url", "https://example.com/free")); assertEquals(calls, http.apiCalls)
            val prefName = "fixture-${UUID.randomUUID()}"
            val isolated = object : android.content.ContextWrapper(context) { override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefName, mode) }
            try {
                val preferences = Enhancements(isolated); assertFalse(preferences.enabled(ProviderCapabilities.DEEPSEEK_WEB))
                preferences.enable(ProviderCapabilities.DEEPSEEK_WEB, true)
                assertTrue(Enhancements(isolated).enabled(ProviderCapabilities.DEEPSEEK_WEB))
                preferences.enable(ProviderCapabilities.DEEPSEEK_WEB, false)
                assertFalse(Enhancements(isolated).enabled(ProviderCapabilities.DEEPSEEK_WEB))
            } finally { context.deleteSharedPreferences(prefName) }
        } finally { root.deleteRecursively() }
    }
    @Test fun enhancementsAndToolsAreVisibleWithoutEnablingBilling() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard", "am start -W -n ai.capri.app/.MainActivity")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, predicate: (String) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null || node.packageName?.toString() != "ai.capri.app") return null
            if (predicate(node.text?.toString().orEmpty())) return node
            for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
            return null
        }
        suspend fun await(predicate: (String) -> Boolean): android.view.accessibility.AccessibilityNodeInfo {
            repeat(60) { instrumentation.uiAutomation.clearCache(); find(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return it }; delay(100) }
            error("Expected retrieval UI element missing")
        }
        suspend fun click(text: String) {
            var node: android.view.accessibility.AccessibilityNodeInfo? = await { it == text }
            while (node != null && !node.isClickable) node = node.parent
            assertTrue(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        click("运行"); click("增强能力")
        await { it == "DeepSeek 搜索与抓取" }; await { it.contains("5元/千次") }
        click("关闭"); click("能力与扩展 · Tools / MCP / Skills")
        var editor: android.view.accessibility.AccessibilityNodeInfo? = await { it == "搜索工具" }
        while (editor != null && !editor.isEditable) editor = editor.parent
        val args = android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "web_search") }
        assertTrue(editor!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        await { it == "搜索网页" }; await { it == "内置" }; click("详情")
        await { it == "web_search" }; await { it.contains("query") && it.contains("count") }
        click("返回工具列表"); click("关闭")
    }

}
