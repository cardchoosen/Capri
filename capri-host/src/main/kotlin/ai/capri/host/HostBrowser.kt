package ai.capri.host

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.ServiceWorkerPolicy
import com.microsoft.playwright.options.WaitUntilState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Opens an offline snapshot of an operator-approved page in a fresh browser context. */
internal class HostBrowser(private val pages: Map<String, HttpUrl>) {
    private val lock = Mutex()

    suspend fun execute(args: JSONObject): JSONObject {
        if (args.getString("action") == "list")
            return JSONObject().put("pages", JSONArray(pages.map { (name, url) ->
                JSONObject().put("name", name).put("url", url.toString())
            }))
        val name = args.optString("name")
        val url = pages[name] ?: throw WorkspaceFailure("browser_page_not_allowed", "Choose an operator-approved page.")
        return lock.withLock {
            val request = Request.Builder().url(url).header("Accept", "text/html, text/plain").build()
            val (type, bytes) = HostIntegrationHttp.execute(request) { response ->
                if (!response.isSuccessful) throw WorkspaceFailure("browser_fetch_error", "The page returned HTTP ${response.code}.")
                val body = checkNotNull(response.body)
                val media = body.contentType()?.let { "${it.type}/${it.subtype}" }
                if (media !in setOf("text/html", "text/plain"))
                    throw WorkspaceFailure("browser_unsupported_page", "The approved page must return HTML or plain text.")
                val bytes = body.byteStream().readNBytes(262_145)
                if (bytes.size > 262_144) throw WorkspaceFailure("browser_page_too_large", "The approved page exceeds 256 KiB.")
                checkNotNull(media) to bytes
            }
            withContext(Dispatchers.IO) { snapshot(name, url, type, bytes) }
        }
    }

    private fun snapshot(name: String, source: HttpUrl, type: String, content: ByteArray): JSONObject {
        val preview = "https://capri-browser.invalid/"
        try {
            Playwright.create(Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1"))).use { engine ->
                engine.chromium().launch(BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true)
                    .setChromiumSandbox(true).setTimeout(10_000.0)).use { browser ->
                    browser.newContext(Browser.NewContextOptions().setJavaScriptEnabled(false)
                        .setAcceptDownloads(false).setServiceWorkers(ServiceWorkerPolicy.BLOCK)).use { context ->
                        context.setDefaultTimeout(5_000.0)
                        context.route("**/*") { route ->
                            if (route.request().url() == preview && route.request().isNavigationRequest)
                                route.fulfill(Route.FulfillOptions().setContentType(type)
                                    .setHeaders(mapOf("Content-Security-Policy" to "default-src 'none'; base-uri 'none'; form-action 'none'; sandbox"))
                                    .setBodyBytes(content))
                            else route.abort()
                        }
                        val page = context.newPage()
                        page.navigate(preview, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED))
                        val excerpt = page.locator("body").ariaSnapshot()
                        return JSONObject().put("name", name).put("url", source.toString()).put("title", page.title().take(300))
                            .put("snapshot", excerpt.take(12_000)).put("truncated", excerpt.length > 12_000)
                    }
                }
            }
        } catch (_: PlaywrightException) {
            throw WorkspaceFailure("browser_unavailable", "The isolated Chrome preview could not be completed.")
        }
    }
}
