package ai.capri.host

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A single isolated browser profile controlled through Playwright's stable browser primitives. */
internal class HostBrowser {
    private val lock = Mutex()
    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var page: Page? = null

    suspend fun execute(args: JSONObject): JSONObject = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                executeLocked(args)
            } catch (failure: WorkspaceFailure) {
                throw failure
            } catch (failure: Exception) {
                throw WorkspaceFailure("browser_action_failed", failure.message?.take(300) ?: "The browser action failed.")
            }
        }
    }

    private fun executeLocked(args: JSONObject): JSONObject {
        val action = args.getString("action")
        if (action == "status") return JSONObject().put("ready", page != null)
            .put("url", page?.url().orEmpty()).put("title", page?.title().orEmpty())
        if (action == "close") {
            runCatching { browser?.close() }; runCatching { playwright?.close() }
            browser = null; playwright = null; page = null
            return JSONObject().put("closed", true)
        }
        val active = ensurePage()
        val selector = args.optString("selector").takeIf(String::isNotBlank)
        val locator = selector?.let(active::locator)
        return when (action) {
            "open" -> {
                val url = validateNavigation(args.getString("url"))
                active.navigate(url, Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(timeout(args)))
                JSONObject().put("url", active.url()).put("title", active.title())
            }
            "snapshot" -> {
                val text = active.locator("body").ariaSnapshot()
                JSONObject().put("url", active.url()).put("title", active.title())
                    .put("snapshot", text.take(16_000)).put("truncated", text.length > 16_000)
            }
            "click" -> { checkLocator(locator).click(Locator.ClickOptions().setTimeout(timeout(args))); JSONObject().put("clicked", selector) }
            "fill" -> { checkLocator(locator).fill(args.getString("text"), Locator.FillOptions().setTimeout(timeout(args))); JSONObject().put("filled", selector) }
            "type" -> { checkLocator(locator).type(args.getString("text"), Locator.TypeOptions().setTimeout(timeout(args))); JSONObject().put("typed", selector) }
            "press" -> { (locator ?: active.locator("body")).press(args.getString("key"), Locator.PressOptions().setTimeout(timeout(args))); JSONObject().put("pressed", args.getString("key")) }
            "hover" -> { checkLocator(locator).hover(Locator.HoverOptions().setTimeout(timeout(args))); JSONObject().put("hovered", selector) }
            "check" -> { checkLocator(locator).check(Locator.CheckOptions().setTimeout(timeout(args))); JSONObject().put("checked", selector) }
            "uncheck" -> { checkLocator(locator).uncheck(Locator.UncheckOptions().setTimeout(timeout(args))); JSONObject().put("unchecked", selector) }
            "select" -> {
                val values = args.optJSONArray("values") ?: JSONArray().put(args.getString("value"))
                val selected = (0 until values.length()).map(values::getString).toTypedArray()
                val result = checkLocator(locator).selectOption(selected, Locator.SelectOptionOptions().setTimeout(timeout(args)))
                JSONObject().put("selected", JSONArray(result.toList()))
            }
            "scroll" -> {
                val delta = args.optInt("delta", 600).coerceIn(-5000, 5000)
                active.evaluate("delta => window.scrollBy(0, delta)", delta)
                JSONObject().put("scrolled", delta)
            }
            "wait" -> {
                if (locator != null) locator.waitFor(Locator.WaitForOptions().setTimeout(timeout(args)))
                else active.waitForLoadState(LoadState.DOMCONTENTLOADED, Page.WaitForLoadStateOptions().setTimeout(timeout(args)))
                JSONObject().put("ready", true).put("url", active.url())
            }
            "text" -> {
                val text = (locator ?: active.locator("body")).innerText()
                JSONObject().put("text", text.take(16_000)).put("truncated", text.length > 16_000)
            }
            "back" -> { active.goBack(); JSONObject().put("url", active.url()).put("title", active.title()) }
            "forward" -> { active.goForward(); JSONObject().put("url", active.url()).put("title", active.title()) }
            "reload" -> { active.reload(); JSONObject().put("url", active.url()).put("title", active.title()) }
            "test" -> runPlan(active, args.getJSONArray("steps"))
            else -> throw WorkspaceFailure("invalid_browser_action", "Choose a supported browser action.")
        }
    }

    private fun runPlan(active: Page, steps: JSONArray): JSONObject {
        if (steps.length() !in 1..40) throw WorkspaceFailure("invalid_playwright_plan", "A Playwright plan must contain 1 to 40 steps.")
        val results = JSONArray()
        for (index in 0 until steps.length()) {
            val step = steps.getJSONObject(index)
            val op = step.getString("op")
            val selector = step.optString("selector").takeIf(String::isNotBlank)
            val target = selector?.let(active::locator)
            val result = try {
                when (op) {
                    "goto" -> { active.navigate(validateNavigation(step.getString("url"))); JSONObject().put("url", active.url()) }
                    "click" -> { checkLocator(target).click(); JSONObject().put("clicked", selector) }
                    "fill" -> { checkLocator(target).fill(step.getString("text")); JSONObject().put("filled", selector) }
                    "press" -> { (target ?: active.locator("body")).press(step.getString("key")); JSONObject().put("pressed", step.getString("key")) }
                    "hover" -> { checkLocator(target).hover(); JSONObject().put("hovered", selector) }
                    "check" -> { checkLocator(target).check(); JSONObject().put("checked", selector) }
                    "uncheck" -> { checkLocator(target).uncheck(); JSONObject().put("unchecked", selector) }
                    "select" -> { JSONObject().put("selected", JSONArray(checkLocator(target).selectOption(step.getString("value")).toList())) }
                    "wait" -> { if (target != null) target.waitFor() else active.waitForLoadState(LoadState.DOMCONTENTLOADED); JSONObject().put("ready", true) }
                    "assert_visible" -> { if (!checkLocator(target).isVisible) throw IllegalStateException("The locator is not visible."); JSONObject().put("visible", selector) }
                    "assert_url" -> {
                        val expected = step.getString("text")
                        if (expected !in active.url()) throw IllegalStateException("Expected URL text was not found: ${expected.take(160)}")
                        JSONObject().put("assertedUrl", active.url())
                    }
                    "assert_count" -> {
                        val expected = step.getInt("count")
                        val actual = checkLocator(target).count()
                        if (actual != expected) throw IllegalStateException("Expected $expected matching elements; found $actual.")
                        JSONObject().put("assertedCount", actual)
                    }
                    "assert_text" -> {
                        val expected = step.getString("text")
                        val actual = (target ?: active.locator("body")).innerText()
                        if (expected !in actual) throw IllegalStateException("Expected text was not found: ${expected.take(160)}")
                        JSONObject().put("assertedText", expected)
                    }
                    "assert_title" -> {
                        val expected = step.getString("text")
                        val actual = active.title()
                        if (expected !in actual) throw IllegalStateException("Expected title was not found: ${expected.take(160)}")
                        JSONObject().put("assertedTitle", actual)
                    }
                    else -> throw WorkspaceFailure("invalid_playwright_step", "Unsupported Playwright step at index $index.")
                }
            } catch (failure: WorkspaceFailure) {
                throw failure
            } catch (failure: Exception) {
                return JSONObject().put("passed", false).put("failedStep", index).put("operation", op)
                    .put("error", failure.message?.take(500) ?: "The step failed.").put("completedSteps", results)
            }
            results.put(JSONObject().put("index", index).put("op", op).put("result", result))
        }
        return JSONObject().put("passed", true).put("steps", results)
    }

    private fun ensurePage(): Page {
        page?.let { return it }
        val engine = Playwright.create(Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")))
        try {
            val launched = engine.chromium().launch(BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true)
                .setChromiumSandbox(true).setTimeout(15_000.0))
            val context = launched.newContext(Browser.NewContextOptions().setAcceptDownloads(false)
                .setServiceWorkers(com.microsoft.playwright.options.ServiceWorkerPolicy.BLOCK))
            context.setDefaultTimeout(10_000.0)
            val active = context.newPage()
            playwright = engine; browser = launched; page = active
            return active
        } catch (failure: Exception) {
            runCatching { engine.close() }
            throw WorkspaceFailure("browser_unavailable", "The isolated Chrome browser could not be started. Install Chrome and the Playwright browser runtime.")
        }
    }

    private fun checkLocator(locator: Locator?): Locator = locator
        ?: throw WorkspaceFailure("missing_browser_selector", "Provide a selector for this browser action.")

    private fun timeout(args: JSONObject): Double = (args.optInt("timeoutMs", 10_000).coerceIn(500, 30_000)).toDouble()

    private fun validateNavigation(raw: String): String {
        val url = raw.trim().toHttpUrlOrNull()
            ?: throw WorkspaceFailure("invalid_browser_url", "Use a valid HTTPS or local HTTP URL.")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
            !(url.isHttps || url.scheme == "http" && url.host in setOf("127.0.0.1", "::1", "localhost")))
            throw WorkspaceFailure("invalid_browser_url", "Browser navigation is limited to HTTPS and loopback HTTP URLs.")
        return url.toString()
    }
}
