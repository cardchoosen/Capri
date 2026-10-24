package ai.capri.app.retrieval

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class RetrievalFailure(val category: String) : java.io.IOException(category)
internal object PublicUrls {
    fun parse(raw: String): HttpUrl {
        require(raw.length <= 8192) { "网址超过8192字符" }
        val url = raw.toHttpUrlOrNull() ?: throw RetrievalFailure("invalid_url")
        if (url.scheme !in setOf("http", "https") || url.username.isNotEmpty() || url.password.isNotEmpty()) throw RetrievalFailure("invalid_url")
        if (url.host.lowercase() in setOf("localhost", "localhost.localdomain") || url.host.endsWith(".localhost") || url.host.endsWith(".local")) throw RetrievalFailure("non_public_url")
        if (url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) {
            if (!publicAddress(InetAddress.getByName(url.host))) throw RetrievalFailure("non_public_url")
        }
        return url.newBuilder().fragment(null).build()
    }
    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        return if (b.size == 4) !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
            (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
            (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] in setOf(0, 168)) || (b[0] == 198 && b[1] in 18..19))
        else (b[0] and 0xfe) != 0xfc // IPv6 ULA is not covered by isSiteLocalAddress.
    }
    fun canonical(raw: String): String? = runCatching {
        val u = parse(raw); val builder = u.newBuilder().fragment(null)
        u.queryParameterNames.filter { it.startsWith("utm_", true) || it in setOf("gclid", "fbclid") }.forEach { builder.removeAllQueryParameters(it) }
        builder.build().toString()
    }.getOrNull()
}
internal data class HttpPage(val url: String, val status: Int, val mime: String, val bytes: ByteArray) {
    fun document() = Jsoup.parse(bytes.inputStream(), null, url)
    fun text() = bytes.toString(Charsets.UTF_8)
}
/** No cookies or browser state. Redirects and DNS targets are checked before each request. */
internal interface RetrievalHttp {
    suspend fun get(raw: String, head: Boolean = false): HttpPage
    suspend fun api(url: String, body: String, key: String): HttpPage
}
internal class PublicHttp(allowPrivateForTests: Boolean = false, builder: OkHttpClient.Builder = OkHttpClient.Builder()) : RetrievalHttp {
    private val client = builder.followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES).apply { if (!allowPrivateForTests) dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !PublicUrls.publicAddress(it) }) throw UnknownHostException("non_public_address")
            }
        }) }.build()
    override suspend fun get(raw: String, head: Boolean): HttpPage {
        var url = PublicUrls.parse(raw)
        // Public retrieval uses HTTPS even when a search index supplied a legacy HTTP link.
        if (!url.isHttps) url = url.newBuilder().scheme("https").port(if (url.port == 80) 443 else url.port).build()
        repeat(5) { redirect ->
            val page = request(url, head, null, null)
            if (page.status !in setOf(301, 302, 303, 307, 308)) return page
            // Redirect location is returned only by the internal request wrapper.
            val location = page.mime.removePrefix("redirect:")
            if (!page.mime.startsWith("redirect:") || redirect == 4) throw RetrievalFailure("redirect_limit")
            val next = url.resolve(location) ?: throw RetrievalFailure("invalid_redirect")
            if (url.isHttps && !next.isHttps) throw RetrievalFailure("insecure_redirect")
            url = PublicUrls.parse(next.toString())
        }
        throw RetrievalFailure("redirect_limit")
    }
    override suspend fun api(url: String, body: String, key: String): HttpPage {
        val target = PublicUrls.parse(url)
        require(target.isHttps && target.host == "api.deepseek.com" && target.port == 443 && target.encodedPath in setOf("/v1/tools/search", "/v1/tools/fetch"))
        if (target.encodedPath.endsWith("/fetch")) {
            val fetchedUrl = PublicUrls.parse(org.json.JSONObject(body).getString("url"))
            validateDns(fetchedUrl.host)
        }
        return request(target, false, body, key) // Authenticated requests never follow redirects.
    }
    // Blocking system DNS may outlive cancellation; a bounded daemon pool keeps it off the caller
    // and prevents unbounded thread growth while the retrieval budget can still expire promptly.
    private suspend fun validateDns(host: String): Unit = suspendCancellableCoroutine { continuation ->
        try {
            val future = dnsChecks.submit {
                val result = runCatching {
                    val addresses = Dns.SYSTEM.lookup(host)
                    if (addresses.isEmpty() || addresses.any { !PublicUrls.publicAddress(it) }) throw RetrievalFailure("non_public_url")
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            continuation.resumeWithException(RetrievalFailure("dns_busy"))
        }
    }
    companion object {
        private val dnsChecks = java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(4), java.util.concurrent.ThreadFactory { task ->
                Thread(task, "capri-public-dns").apply { isDaemon = true }
            })
    }
    private suspend fun request(url: HttpUrl, head: Boolean, json: String?, key: String?): HttpPage = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Accept", if (json == null) "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5" else "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/132.0.0.0 Mobile Safari/537.36 Capri/0.1")
        if (key != null) request.header("Authorization", "Bearer $key")
        if (head) request.head()
        if (json != null) request.post(json.toRequestBody("application/json".toMediaType()))
        val call = client.newCall(request.build()); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { r ->
                    if (r.isRedirect) HttpPage(r.request.url.toString(), r.code, "redirect:" + r.header("Location").orEmpty(), byteArrayOf())
                    else {
                        val body = r.body; val max = 3_000_000
                        if (!head && (body?.contentLength() ?: 0) > max) throw RetrievalFailure("body_too_large")
                        val bytes = java.io.ByteArrayOutputStream()
                        if (!head) body?.byteStream()?.use { input ->
                            val buffer = ByteArray(8192)
                            while (true) { val count = input.read(buffer); if (count < 0) break; if (bytes.size() + count > max) throw RetrievalFailure("body_too_large"); bytes.write(buffer, 0, count) }
                        }
                        HttpPage(r.request.url.toString(), r.code, r.header("Content-Type").orEmpty(), bytes.toByteArray())
                    }
                } }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
}
