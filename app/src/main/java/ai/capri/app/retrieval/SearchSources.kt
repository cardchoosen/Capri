package ai.capri.app.retrieval

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

internal data class SearchHit(val title: String, val url: String, val snippet: String, val published: String? = null, val content: String? = null)
internal data class SearchBatch(val status: String, val hits: List<SearchHit>)
internal enum class SearchSource(val id: String, val domestic: Boolean, val base: String, val path: String, val parameter: String) {
    DDG("duckduckgo", false, "https://html.duckduckgo.com", "/html/", "q"),
    BING("bing", false, "https://www.bing.com", "/search", "q"),
    BING_CN("bing-cn", true, "https://cn.bing.com", "/search", "q"),
    BAIDU("baidu", true, "https://www.baidu.com", "/s", "wd"),
    SOGOU("sogou", true, "https://www.sogou.com", "/web", "query");
    fun query(query: String) = (base + path).toHttpUrl().newBuilder().addQueryParameter(parameter, query).build().toString()
}
/** Independently implemented adapters for public result pages. No script execution or browser automation. */
internal object SearchPages {
    fun parse(source: SearchSource, page: HttpPage): SearchBatch {
        if (page.status !in 200..299) return SearchBatch(if (page.status == 429) "rate_limited" else "http_${page.status}", emptyList())
        val doc = page.document()
        val title = doc.title().lowercase()
        if (listOf("captcha", "安全验证", "人机验证", "访问过于频繁").any { title.contains(it) } || doc.select("form#challenge-form,form[action*=anomaly],#captcha").isNotEmpty()) return SearchBatch("captcha", emptyList())
        val hits = when (source) {
            SearchSource.DDG -> doc.select(".result").mapNotNull { block -> hit(block.selectFirst("a.result__a"), block.selectFirst(".result__snippet")?.text().orEmpty()) }
            SearchSource.BING, SearchSource.BING_CN -> doc.select("li.b_algo").mapNotNull { block -> hit(block.selectFirst("h2 a") ?: block.selectFirst("h2")?.closest("a"), block.selectFirst(".b_caption p,.b_snippet p")?.text().orEmpty()) }
            SearchSource.BAIDU -> doc.select("h3").mapNotNull { heading ->
                val block = heading.closest(".result,.c-container,.c-result,[tpl],[class*=result]") ?: heading.parent()
                val link = heading.selectFirst("a") ?: block?.selectFirst("a")
                val metadataUrl = block?.attr("mu")?.takeIf { it.isNotBlank() } ?: runCatching { JSONObject(block?.attr("data-log").orEmpty()).optString("mu") }.getOrNull()
                if (link != null) hit(link, block?.text().orEmpty().removePrefix(heading.text()).take(1200))
                else metadataUrl?.takeIf { it.isNotBlank() }?.let { target ->
                    val canonical = PublicUrls.canonical(target) ?: return@let null
                    SearchHit(heading.text().take(300), canonical, block?.text().orEmpty().removePrefix(heading.text()).take(1200))
                }
            }
            SearchSource.SOGOU -> doc.select("h3,.vr-title,.vrTitle,.result-title").mapNotNull { heading ->
                val block = heading.closest(".rb,.vrwrap,.results,.vr-result,.result,[class*=result]") ?: heading.parent()
                hit(heading.selectFirst("a") ?: heading.closest("a"), block?.text().orEmpty().removePrefix(heading.text()).take(1200))
            }
        }.distinctBy { it.url }.take(10)
        if (hits.isNotEmpty()) return SearchBatch("ok", hits)
        val text = doc.body().text().take(2500).lowercase()
        if (listOf("verify you are human", "unfortunately, bots", "验证码", "安全验证", "antispider").any { text.contains(it) } || URI(page.url).path.contains("antispider")) return SearchBatch("captcha", emptyList())
        val empty = doc.select(".no-results,.b_no,#no-result,.nors").isNotEmpty() ||
            listOf("no results found", "没有找到相关", "未找到相关结果", "抱歉，没有找到").any { text.contains(it) }
        return SearchBatch(if (empty) "empty" else "parse_failed", emptyList())
    }
    private fun hit(anchor: Element?, snippet: String): SearchHit? {
        if (anchor == null) return null
        var url = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
        if (parsed.host.endsWith("duckduckgo.com")) url = parsed.queryParameter("uddg") ?: return null
        else if (parsed.host.endsWith("bing.com") && parsed.encodedPath.startsWith("/ck/")) {
            val encoded = parsed.queryParameter("u") ?: return null
            url = runCatching { String(java.util.Base64.getUrlDecoder().decode(encoded.removePrefix("a1"))) }.getOrNull() ?: return null
        }
        else if (parsed.host == "sogou.com" || parsed.host.endsWith(".sogou.com")) {
            url = parsed.queryParameter("pcurl") ?: parsed.queryParameter("url") ?: url
            val target = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
            if (target.host.endsWith("sogou.com") && target.encodedPath.contains("searchList")) return null
        }
        val canonical = PublicUrls.canonical(url) ?: return null
        val title = anchor.text().trim()
        if (title.isBlank() || title.length > 1000) return null
        return SearchHit(title.take(300), canonical, snippet.take(1200))
    }
    fun api(data: JSONObject): List<SearchHit> {
        val results = data.getJSONArray("results")
        return (0 until results.length()).mapNotNull { index ->
            val item = results.getJSONObject(index); val url = PublicUrls.canonical(item.getString("url")) ?: return@mapNotNull null
            SearchHit(item.getString("title").take(300), url, item.optString("summary").ifBlank { item.optString("snippet") }.take(1600),
                item.optString("date").takeUnless { it.isBlank() || it == "null" })
        }.distinctBy { it.url }.take(10)
    }
}
