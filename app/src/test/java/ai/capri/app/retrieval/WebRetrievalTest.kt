package ai.capri.app.retrieval

import ai.capri.app.ModelConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class WebRetrievalTest {
    private fun page(body: String, url: String = "https://example.com/article", code: Int = 200) = HttpPage(url, code, "text/html;charset=utf-8", body.toByteArray())
    @Test fun untrustedUrlsAndAddressesCannotReachPrivateTargets() {
        for (url in listOf("file:///tmp/secret", "http://localhost/a", "https://127.0.0.1", "http://2130706433", "http://[::1]", "https://[fc00::1]", "https://u:p@example.com")) assertTrue(url, runCatching { PublicUrls.parse(url) }.isFailure)
        for (ip in listOf("10.0.0.1", "172.20.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "::", "fe80::1", "fc00::1")) assertFalse(ip, PublicUrls.publicAddress(InetAddress.getByName(ip)))
        assertTrue(PublicUrls.publicAddress(InetAddress.getByName("8.8.8.8")))
        assertEquals("https://example.com/a?q=1", PublicUrls.canonical("https://example.com/a?q=1&utm_source=x#fragment"))
    }
    @Test fun resultAdaptersHandleMobileMarkupRedirectsAndFailures() {
        val ddg = page("<div class=result><a class=result__a href='https://duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs'>Kotlin</a><span class=result__snippet>Official documentation</span></div>")
        assertEquals("https://kotlinlang.org/docs", SearchPages.parse(SearchSource.DDG, ddg).hits.single().url)
        val bing = page("<li class=b_algo><a href='https://example.com/docs'><h2>文档</h2></a><div class=b_caption><p>正文摘要</p></div></li>")
        assertEquals("正文摘要", SearchPages.parse(SearchSource.BING_CN, bing).hits.single().snippet)
        val baidu = page("<div class='c-result result' data-log='{\"mu\":\"https://example.com/baidu\"}'><h3><span>技术资料</span></h3><p>结果摘要</p></div>")
        assertEquals("https://example.com/baidu", SearchPages.parse(SearchSource.BAIDU, baidu).hits.single().url)
        val sogou = page("<div class=rb><h3><a href='https://m.sogou.com/tc?url=https%3A%2F%2Fexample.com%2Fsogou'>搜狗结果</a></h3><p>摘要</p></div>")
        assertEquals("https://example.com/sogou", SearchPages.parse(SearchSource.SOGOU, sogou).hits.single().url)
        assertEquals("captcha", SearchPages.parse(SearchSource.BAIDU, page("<title>百度安全验证</title>验证码")).status)
        assertEquals("empty", SearchPages.parse(SearchSource.DDG, page("<div class=no-results>No results found</div>")).status)
        assertEquals("parse_failed", SearchPages.parse(SearchSource.DDG, page("<h1>Something changed</h1>")).status)
        assertEquals("rate_limited", SearchPages.parse(SearchSource.DDG, page("Slow down", code = 429)).status)
    }
    @Test fun explicitSitesAndWeakKeywordCoverageAreNotPretendedToMatch() {
        val hits = listOf(SearchHit("Android home", "https://developer.android.google.cn/", "General Android tools"),
            SearchHit("Kotlin 协程", "https://developer.android.google.cn/kotlin/coroutines", "Kotlin 协程与launch"),
            SearchHit("Android Kotlin 协程", "https://other.example/", "Relevant but outside requested site"))
        val query = "Android Kotlin 协程 site:developer.android.google.cn"
        val filtered = QueryRules.sites(query, hits)
        assertEquals(2, filtered.size)
        assertFalse(QueryRules.relevant(query, filtered.take(1)))
        assertTrue(QueryRules.relevant(query, filtered))
        assertTrue(QueryRules.sites("-site:developer.android.google.cn", hits).all { it.url.startsWith("https://other.example/") })
        assertEquals(1, QueryRules.sites("site:developer.android.google.cn/kotlin", hits).size)
    }
    @Test fun extractionPreservesTechnicalCodeAndRejectsRenderedShells() {
        val body = "<title>开发指南</title><nav>导航垃圾</nav><main><h1>指南</h1><p>" + "有效正文与参数说明。".repeat(40) + "</p><pre>def hello():\n    print('保留缩进')\n</pre><script>steal()</script><table><tr><td>参数</td><td>数值</td></tr></table></main>"
        val extracted = PageExtractor.extract(page(body))
        assertEquals("开发指南", extracted.title)
        assertTrue(extracted.text.contains("    print('保留缩进')"))
        assertFalse(extracted.text.contains("steal()")); assertFalse(extracted.text.contains("导航垃圾"))
        assertTrue(extracted.text.contains("# 指南")); assertTrue(extracted.text.contains("参数 |"))
        assertTrue(runCatching { PageExtractor.extract(page("<title>App</title><div id=app></div><script>render()</script>")) }.isFailure)
        assertTrue(runCatching { PageExtractor.extract(HttpPage("https://example.com/a.pdf", 200, "application/pdf", byteArrayOf())) }.isFailure)
        assertTrue(runCatching { PageExtractor.extract(page("<div>".repeat(150) + "nested" + "</div>".repeat(150))) }.isFailure)
    }
    @Test fun providerIdentityRequiresOfficialEndpointAndExplicitConfiguration() {
        assertTrue(ProviderCapabilities.deepSeek(ModelConfig("https://api.deepseek.com", "any-model", "fixture")))
        assertTrue(ProviderCapabilities.deepSeek(ModelConfig("https://api.deepseek.com/v1/", "", "fixture")))
        for (base in listOf("https://proxy.example.com/v1", "https://api.deepseek.com.evil.example/v1", "https://api.deepseek.com:444", "https://api.deepseek.com/?key=x", "https://user@api.deepseek.com", "http://api.deepseek.com")) assertFalse(base, ProviderCapabilities.deepSeek(ModelConfig(base, "deepseek-flash", "fixture")))
        assertFalse(ProviderCapabilities.deepSeek(ModelConfig("https://api.deepseek.com", "deepseek", "")))
    }
}
