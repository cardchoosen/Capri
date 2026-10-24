package ai.capri.app

import ai.capri.app.retrieval.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit research; completing the report does not assert that every public engine is usable. */
@RunWith(AndroidJUnit4::class)
class WebResearchDeviceTest {
    @Test fun measurePublicHttpSources() = runBlocking {
        val http = PublicHttp(); val permit = Semaphore(3)
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "web-research").apply { mkdirs() }
        val queries = listOf("Kotlin coroutines documentation", "北京 天气")
        val rows = coroutineScope { SearchSource.entries.flatMap { source -> queries.map { query -> async(Dispatchers.IO) { permit.withPermit {
            val start = System.nanoTime(); val row = JSONObject().put("provider", source.id).put("query", query)
            try {
                val page = http.get(source.query(query)); val parsed = SearchPages.parse(source, page)
                row.put("http", page.status).put("url", page.url).put("title", page.document().title()).put("bytes", page.bytes.size)
                    .put("status", parsed.status).put("count", parsed.hits.size).put("sample", JSONArray(parsed.hits.take(3).map { JSONObject().put("title", it.title).put("url", it.url) }))
                root.resolve("${source.id}-${queries.indexOf(query)}.html").writeBytes(page.bytes)
            } catch (error: Exception) { row.put("error", if (error is RetrievalFailure) error.category else error.javaClass.simpleName) }
            row.put("elapsed_ms", (System.nanoTime() - start) / 1000000); row
        } } } }.awaitAll() }
        val pages = coroutineScope { listOf("https://kotlinlang.org/docs/coroutines-overview.html", "https://developer.android.google.cn/kotlin/coroutines?hl=zh-cn").map { url -> async(Dispatchers.IO) {
            val row = JSONObject().put("url", url); val start = System.nanoTime()
            try { val page = http.get(url); val text = PageExtractor.extract(page); row.put("http", page.status).put("title", text.title).put("characters", text.text.length).put("method", text.method).put("preview", text.text.take(500)) }
            catch (e: Exception) { row.put("error", if (e is RetrievalFailure) e.category else e.javaClass.simpleName) }
            row.put("elapsed_ms", (System.nanoTime() - start) / 1000000)
        } }.awaitAll() }
        root.resolve("report.json").writeText(JSONObject().put("searches", JSONArray(rows)).put("pages", JSONArray(pages)).toString(2))
    }
}
