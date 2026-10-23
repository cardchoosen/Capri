package ai.capri.app.browser

import ai.capri.app.Workspace
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class BrowserUi(val chat: String = "", val page: String? = null, val revision: Int = 0, val notice: String = "", val busy: Boolean = false)
internal class BrowserFault(val status: String, message: String) : Exception(message)

/** Native browser engine; no shell, CDP socket, JavascriptInterface or arbitrary JS model tool. */
internal class BrowserController(private val context: Context, workspace: Workspace, private val selectedChat: () -> String) {
    private class Page(val chat: String, val record: BrowserRecord, var web: WebView? = null, var epoch: Long = 0,
                       var loading: Boolean = false, var failure: String? = null, var snapshot: String? = null,
                       var paused: Boolean = false, var control: Long = 0)
    private val store = BrowserStore(workspace)
    private val receipts = BrowserOperations(workspace)
    private val actionScript by lazy { context.assets.open("browser/actions.js").bufferedReader().use { it.readText() } }
    private val pages = linkedMapOf<String, MutableList<Page>>() // Main thread only.
    private val operation = Mutex()
    private val loadLocks = mutableMapOf<String, Mutex>()
    private val saveLocks = mutableMapOf<String, Mutex>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var pagesChanged: (String, List<BrowserRecord>) -> Unit = { _, _ -> }
    var displayedPage: () -> String? = { null }
    private val mutableUi = MutableStateFlow(BrowserUi())
    val ui = mutableUi.asStateFlow()
    private var resumed = false
    private var disposed = false
    private val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val observer by lazy { context.assets.open("browser/observe.js").bufferedReader().use { it.readText() } }
    fun status(): JSONObject {
        val provider = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        val supported = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
        return JSONObject().put("status", if (supported) "ok" else "unsupported")
            .put("multi_profile", supported).put("provider", provider?.packageName ?: "unavailable")
            .put("version", provider?.versionName ?: "unknown").put("engine", "Android WebView")
            .put("message", if (supported) "支持会话独立登录环境" else "系统WebView不支持多Profile，请更新WebView；不会共享登录态降级")
            .put("actions", JSONArray(listOf("status", "tabs", "open", "observe", "show", "close", "navigate", "back", "click", "fill", "select", "scroll", "wait", "takeover", "resume")))
    }
    private fun changed(notice: String = mutableUi.value.notice) {
        mutableUi.value = mutableUi.value.copy(revision = mutableUi.value.revision + 1, notice = notice)
        pages.forEach { (chat, _) -> pagesChanged(chat, tabList(chat)) }
    }
    fun isPaused(id: String) = pages.values.flatten().firstOrNull { it.record.id == id }?.paused == true
    fun userTakeover(id: String) {
        pages.values.flatten().firstOrNull { it.record.id == id }?.let { it.paused = true; it.control++; it.snapshot = null }
        changed("用户已接管，自动写操作暂停")
    }
    fun userResume(id: String) {
        pages.values.flatten().firstOrNull { it.record.id == id }?.let { it.paused = false; it.control++; it.snapshot = null }
        changed("自动操作已恢复，请重新读取页面")
    }
    fun hostResumed(value: Boolean) { resumed = value }
    fun selectionChanged(chat: String) { changed() }
    fun view(id: String): WebView? = pages.values.flatten().firstOrNull { it.record.id == id }?.web
    fun liveViews(chat: String) = pages[chat].orEmpty().mapNotNull { p -> p.web?.let { p.record.id to it } }
    fun tabList(chat: String): List<BrowserRecord> = pages[chat].orEmpty().map { it.record.copy() }
    /** User close is immediate, including while a page is loading. No retry or page resurrection. */
    fun closePreview(chat: String, id: String) {
        val p = pages[chat]?.firstOrNull { it.record.id == id } ?: return
        destroy(p); pages[chat]!!.remove(p); changed("")
        scope.launch { runCatching { persist(chat) }.onFailure { changed("标签保存未完成") } }
    }
    private suspend fun load(chat: String): MutableList<Page> {
        require(chat.matches(Regex("[a-zA-Z0-9-]+")))
        return loadLocks.getOrPut(chat) { Mutex() }.withLock {
            pages[chat] ?: withContext(Dispatchers.IO) { store.load(chat) }.map { Page(chat, it) }.toMutableList().also { pages[chat] = it; changed() }
        }
    }

    private suspend fun persist(chat: String) {
        val lock = saveLocks.getOrPut(chat) { Mutex() }
        lock.lock()
        try {
            val records = pages[chat].orEmpty().map { it.record.copy() }
            withContext(Dispatchers.IO) { store.save(chat, records) }
        } finally { lock.unlock() }
    }
    private fun page(chat: String, id: String) = pages[chat]?.firstOrNull { it.record.id == id }
        ?: throw BrowserFault("invalid_argument", "页面不属于当前会话或已关闭")
    suspend fun execute(chat: String, raw: JSONObject): String = withContext(Dispatchers.Main) {
        val start = System.nanoTime()
        val args = try { BrowserContract.validate(raw) } catch (e: Exception) {
            return@withContext JSONObject().put("status", "invalid_argument").put("message", e.message).toString()
        }
        if (disposed) return@withContext JSONObject().put("status", "interrupted").toString()
        if (args.getString("action") == "status") return@withContext status().toString()
        if (args.getString("action") == "tabs") {
            load(chat)
            return@withContext JSONObject().put("status", "ok").put("tabs", JSONArray(tabList(chat).map {
                JSONObject().put("page_id", it.id).put("url", it.url).put("title", it.title)
            })).toString()
        }
        if (!operation.tryLock()) return@withContext JSONObject().put("status", "browser_busy").put("message", "另一个浏览器操作正在执行").toString()
        var active: Page? = null
        var initiatedLoad = false
        var createdView = false
        val isWrite = args.getString("action") in BrowserContract.writes
        try {
            val action = args.getString("action")
            val result = withTimeout(20_000) {
                load(chat)
                if (chat != selectedChat()) throw BrowserFault("needs_foreground", "请先切换到目标聊天，再操作浏览器")
                if (action == "close") {
                    val p = page(chat, args.getString("page_id")); destroy(p); pages[chat]!!.remove(p)
                    if (mutableUi.value.page == p.record.id) mutableUi.value = mutableUi.value.copy(page = null)
                    persist(chat); changed()
                    return@withTimeout JSONObject().put("status", "ok").put("closed", true).put("page_id", p.record.id)
                }
                if (!status().getBoolean("multi_profile")) throw BrowserFault("unsupported", "请更新系统WebView以支持会话隔离；浏览器未创建")
                val p = if (action == "open") {
                    val url = BrowserContract.url(args.getString("url"), debug)
                    if (pages[chat]!!.size >= 5) throw BrowserFault("limit", "当前聊天最多5个标签页，请先关闭不需要的页面")
                    Page(chat, BrowserRecord(UUID.randomUUID().toString(), url)).also { pages[chat]!!.add(it) }
                } else page(chat, args.getString("page_id"))
                active = p
                if (action in setOf("takeover", "resume")) {
                    if (action == "takeover") userTakeover(p.record.id) else userResume(p.record.id)
                    return@withTimeout JSONObject().put("status", "ok").put("page_id",p.record.id).put("paused",p.paused).put("requires_fresh_observation",true)
                }
                if ((action in BrowserContract.writes || action == "wait") && p.web == null)
                    throw BrowserFault("needs_observation", "页面已重建或未就绪，请先show/observe；不会恢复并重放写操作")
                val restored = p.web == null
                createdView = restored
                ensureView(p)
                mutableUi.value = BrowserUi(chat, p.record.id, mutableUi.value.revision + 1, if (restored) "正在打开页面" else "", busy = true)
                changed()
                awaitHost(p)
                if (action in BrowserContract.writes || action == "wait") return@withTimeout interact(p, args)
                if (restored) {
                    initiatedLoad = true; p.loading = true; p.failure = null
                    p.web!!.loadUrl(BrowserContract.url(p.record.url, debug))
                }
                while (p.loading) { ensureHost(p); p.failure?.let { throw BrowserFault("load_failed", it) }; delay(50) }
                p.failure?.let { throw BrowserFault("load_failed", it) }
                val snapshot = observe(p)
                persist(chat); changed("")
                JSONObject().put("status", "ok").put("page_id", p.record.id).put("document_epoch", p.epoch)
                    .put("recreated", restored && action != "open").put("observation", snapshot)
            }
            result.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000).toString()
        } catch (timeout: TimeoutCancellationException) {
            if (initiatedLoad) { active?.failure = "页面加载超时，尚未确认可用"; active?.web?.stopLoading() }
            JSONObject().put("status", "timeout").put("page_id", active?.record?.id ?: JSONObject.NULL)
                .put("action_state", if(isWrite) "unknown" else "not_applied")
                .put("message", "页面等待超时，未确认加载成功；标签保留，不自动重放").withNetworkDiagnostic(active?.record?.url).toString()
        } catch (cancel: CancellationException) {
            if (initiatedLoad) active?.web?.stopLoading()
            throw cancel
        } catch (e: Exception) {
            if (initiatedLoad) active?.web?.stopLoading()
            JSONObject().put("status", (e as? BrowserFault)?.status ?: "error").put("page_id", active?.record?.id ?: JSONObject.NULL)
                .put("message", if (e is BrowserFault || e is IllegalArgumentException) e.message else "浏览器操作未完成，可查看页面后重试")
                .let { if (e is BrowserFault && e.status in setOf("load_failed", "not_loaded")) it.withNetworkDiagnostic(active?.record?.url) else it }.toString()
        } finally {
            active?.let { if (initiatedLoad) it.loading = false else if (createdView) destroy(it) }
            mutableUi.value = mutableUi.value.copy(busy = false); changed(); operation.unlock()
        }
    }
    private suspend fun JSONObject.withNetworkDiagnostic(url: String?): JSONObject {
        if (url?.startsWith("https://") != true) return this
        try { put("network_diagnostic", ai.capri.app.network.NetworkReachability.check(context, url)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { put("network_diagnostic", JSONObject().put("status", "unavailable")) }
        return this
    }
    private fun ensureHost(p: Page) {
        if (!resumed || selectedChat() != p.chat)
            throw BrowserFault("needs_foreground", "请保持Capri及目标聊天在前台；无需切换网页预览")
        if (p.web == null) throw BrowserFault("renderer_gone", "页面已中断，请重新打开")
    }
    private suspend fun awaitHost(p: Page) {
        // The Compose dialog may not have mounted when show was requested.
        val until = System.nanoTime() + 3_000_000_000
        while (!resumed || p.web?.isAttachedToWindow != true || p.web!!.width == 0) {
            if (selectedChat() != p.chat || System.nanoTime() > until)
                throw BrowserFault("needs_foreground", "浏览器工作区未挂载，请保持Capri在前台后重试")
            delay(25)
        }
        ensureHost(p)
    }
    private fun destroy(p: Page) {
        p.epoch++; p.snapshot = null; p.loading = false
        p.web?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        p.web = null
    }
    @Suppress("SetJavaScriptEnabled")
    private fun ensureView(p: Page) {
        if (p.web != null) return
        val live = pages.values.flatten().filter { it.web != null }
        if (live.size >= 2) destroy(live.firstOrNull { it.record.id != displayedPage() } ?: live.first())
        val web = WebView(context)
        try {
            WebViewCompat.setProfile(web, "capri_chat_${p.chat}")
            web.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(true)
                setGeolocationEnabled(false); safeBrowsingEnabled = true
            }
            WebView.setWebContentsDebuggingEnabled(debug)
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    runCatching { BrowserContract.url(request.url.toString(), debug) }.isFailure.also {
                        if (it) changed("此链接需要其他应用或不支持的协议，未打开")
                    }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (p.web !== view) return
                    p.epoch++; p.snapshot = null; p.loading = true
                    if (runCatching { BrowserContract.url(url.orEmpty(), debug) }.isFailure) {
                        p.failure = "页面导航协议不受支持"; view.stopLoading(); p.loading = false
                    }
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    if (p.web !== view) return
                    p.loading = false
                    if (url != null && runCatching { BrowserContract.url(url, debug) }.isSuccess) p.record.url = url
                    p.record.title = view.title.orEmpty().take(160); changed(p.failure.orEmpty())
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (p.web === view && request.isForMainFrame) { p.failure = "网页加载失败（${error.errorCode}）"; p.loading = false; changed(p.failure!!) }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (p.web === view && request.isForMainFrame && response.statusCode >= 400) { p.failure = "网页HTTP错误（${response.statusCode}）"; changed(p.failure!!) }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel(); p.failure = "网页TLS验证失败"; p.loading = false; changed(p.failure!!)
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    destroy(p); p.failure = "网页渲染进程已中断"; changed(p.failure!!); return true
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) { p.record.title = title.orEmpty().take(160); changed() }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny(); changed("网页设备权限尚未接入") }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
                override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); changed("网页弹窗尚未接入"); return true }
                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); changed("网页确认弹窗尚未接入"); return true }
                override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean { result.cancel(); return true }
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean { callback.onReceiveValue(null); changed("文件上传将在03-C接入"); return true }
            }
            web.setDownloadListener { _, _, _, _, _ -> changed("文件下载将在03-C接入") }
            p.web = web
        } catch (e: Exception) { web.destroy(); throw e }
    }
    private suspend fun observe(p: Page): JSONObject {
        ensureHost(p)
        p.failure?.let { throw BrowserFault("load_failed", it) }
        if (p.web?.url.isNullOrBlank() || p.web?.url == "about:blank" || p.web?.url?.startsWith("chrome-error:") == true)
            throw BrowserFault("not_loaded", "尚无可读取的已加载网页，不能把空白页视为成功")
        val epoch = p.epoch
        val token = UUID.randomUUID().toString()
        val script = observer.replace("__CAPRI_SNAPSHOT__", JSONObject.quote(token))
        val raw = suspendCancellableCoroutine<String> { continuation ->
            p.web!!.evaluateJavascript(script) { value ->
                if (continuation.isActive) continuation.resumeWith(Result.success(value ?: "null"))
            }
        }
        ensureHost(p)
        if (epoch != p.epoch) throw BrowserFault("stale_reference", "读取期间页面已跳转，请重新观察")
        if (raw.toByteArray().size > 64000) throw BrowserFault("output_limit", "页面摘要超出限制")
        return JSONObject(raw).also {
            if (it.optString("snapshot_id") != token) throw BrowserFault("invalid_snapshot", "页面摘要无效")
            p.snapshot = token
        }
    }
    private suspend fun evaluate(p: Page, script: String): JSONObject {
        ensureHost(p)
        val raw = suspendCancellableCoroutine<String> { c ->
            p.web!!.evaluateJavascript(script) { if (c.isActive) c.resumeWith(Result.success(it ?: "null")) }
        }
        ensureHost(p)
        if (raw.toByteArray().size > 64000) throw BrowserFault("output_limit", "操作结果超出限制")
        return runCatching { JSONObject(raw) }.getOrElse { throw BrowserFault("unknown_result", "页面跳转或脚本未返回，不自动重放") }
    }
    private suspend fun interact(p: Page, args: JSONObject): JSONObject {
        val action=args.getString("action")
        val write=action in BrowserContract.writes
        val generation=p.control
        fun guard() {
            ensureHost(p)
            if (p.control != generation || (write && p.paused)) throw BrowserFault("needs_user", "用户已接管；等待用户明确恢复，不自动重放")
        }
        var state="not_applied"
        var begun=false
        var receiptFinished=false
        var snapshot: JSONObject?=null
        val id=args.optString("request_id")
        fun output(status: String, message: String="") = JSONObject().put("status",status).put("page_id",p.record.id)
            .put("action_state",state).put("document_epoch",p.epoch).put("message",message)
        try {
            guard()
            if(action=="navigate") BrowserContract.url(args.getString("url"),debug)
            if (write) {
                val prior=withContext(Dispatchers.IO) { receipts.begin(p.chat,args) }
                if (prior != null && (prior.optString("page_id") != p.record.id || prior.optString("action") != action))
                    throw BrowserFault("request_conflict","此request_id属于另一个页面或动作")
                if (prior != null) return output("duplicate_not_replayed", "已有此请求记录；先观察，不重复执行")
                    .put("action_state",prior.getString("action_state")).put("receipt",prior)
                begun=true
            }
            guard()
            val elementAction=action in setOf("click","fill","select")
            var preparedBounds: JSONArray? = null
            if (elementAction) {
                if (p.snapshot != args.getString("snapshot_id") || p.epoch != args.getLong("document_epoch"))
                    throw BrowserFault("stale_reference", "快照或document_epoch已失效，请重新观察")
                val prepare=JSONObject(args.toString()).put("phase","prepare")
                val ready=evaluate(p,actionScript.replace("__CAPRI_ACTION__",prepare.toString()))
                if (ready.optString("status") != "ok") throw BrowserFault(ready.optString("status"),ready.optString("message"))
                preparedBounds=ready.getJSONArray("bounds")
                delay(80); guard()
                if (p.snapshot != args.getString("snapshot_id") || p.epoch != args.getLong("document_epoch"))
                    throw BrowserFault("stale_reference", "操作前页面已变化")
            }
            if (write) {
                guard(); state="unknown" // From here cancellation/crash must not imply safe replay.
                when(action) {
                    "navigate" -> { val url=BrowserContract.url(args.getString("url"),debug); p.failure=null; p.loading=true; p.snapshot=null; p.web!!.loadUrl(url); state="dispatched" }
                    "back" -> {
                        if (!p.web!!.canGoBack()) { state="not_applied"; throw BrowserFault("no_history","没有后退历史") }
                        p.failure=null; p.loading=true; p.snapshot=null; p.web!!.goBack(); state="dispatched"
                    }
                    "scroll" -> {
                        val direction=args.getString("direction")
                        val delta=when(direction) { "up" -> "0,-innerHeight*.8"; "down" -> "0,innerHeight*.8"; "left" -> "-innerWidth*.8,0"; else -> "innerWidth*.8,0" }
                        evaluate(p,"(function(){window.scrollBy({left:(${delta.substringBefore(',')}),top:(${delta.substringAfter(',')}),behavior:'instant'});return {status:'ok'};})()")
                        state="dispatched"; p.snapshot=null
                    }
                    else -> {
                        val command=JSONObject(args.toString()).put("bounds",preparedBounds)
                        val result=evaluate(p,actionScript.replace("__CAPRI_ACTION__",command.toString()))
                        state=result.optString("action_state","unknown"); p.snapshot=null
                        if(result.optString("status") != "ok") throw BrowserFault(result.optString("status"),result.optString("message"))
                        if(result.has("value_matches") && !result.getBoolean("value_matches")) throw BrowserFault("verification_failed","控件未接受所填内容")
                    }
                }
                withContext(Dispatchers.IO) { receipts.finish(p.chat,id,state) }; receiptFinished=true
                delay(100); guard()
            }
            val deadline=System.nanoTime()+args.optLong("timeout_ms",5000)*1_000_000
            val expect=args.optJSONObject("expect")
            var matched=false
            while(true) {
                guard()
                p.failure?.let { throw BrowserFault("load_failed",it) }
                if(!p.loading) {
                    snapshot=observe(p)
                    matched=expect?.let { condition ->
                        when {
                            condition.has("text") -> snapshot!!.optString("text").contains(condition.getString("text"))
                            condition.has("text_absent") -> !snapshot!!.optString("text").contains(condition.getString("text_absent")) && !snapshot!!.optBoolean("truncated")
                            else -> snapshot!!.optString("url").contains(condition.getString("url_contains"))
                        }
                    } ?: true
                    if(matched)break
                }
                if(System.nanoTime()>=deadline)break
                delay(150)
            }
            guard(); persist(p.chat); changed("")
            return output(if(matched) "ok" else "verification_timeout")
                .put("observation",snapshot ?: JSONObject.NULL)
                .put("verification",JSONObject().put("state",if(expect==null) "not_requested" else if(matched) "matched" else "timeout"))
        } catch(e:CancellationException) {
            throw e
        } catch(e:Exception) {
            return output((e as? BrowserFault)?.status ?: "error", if(e is BrowserFault || e is IllegalArgumentException) e.message.orEmpty() else "操作结果不确定，请观察；不要重放")
                .put("observation",snapshot ?: JSONObject.NULL).put("verification",JSONObject().put("state","unknown"))
                .let { if(e is BrowserFault && e.status in setOf("load_failed","not_loaded")) it.withNetworkDiagnostic(p.record.url) else it }
        } finally {
            if(begun && !receiptFinished) withContext(NonCancellable+Dispatchers.IO) { runCatching { receipts.finish(p.chat,id,state) } }
        }
    }
    fun dispose() { disposed = true; scope.cancel(); pages.values.flatten().forEach(::destroy) }
}
