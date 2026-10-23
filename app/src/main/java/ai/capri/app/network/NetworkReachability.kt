package ai.capri.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Small, anonymous HTTPS probes on the Android network. Never changes DNS/proxy or uses PRoot. */
internal object NetworkReachability {
    private val references = listOf("https://www.baidu.com/", "https://www.google.com/", "https://github.com/")
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private data class Cached(val network: String, val at: Long, val value: String)
    private val cache = linkedMapOf<String, Cached>()

    fun origin(raw: String): String {
        val url = raw.toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "url必须是无账号密码的HTTPS地址" }
        return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    }
    fun failureKind(error: Throwable): String = when (error) {
        is UnknownHostException -> "dns_failed"
        is SSLException -> "tls_failed"
        is SocketTimeoutException -> "timeout"
        is ConnectException -> "connect_failed"
        else -> "network_error"
    }
    fun sampleState(reachable: List<Boolean>): String = when {
        reachable.isEmpty() -> "unknown"
        reachable.all { it } -> "reachable"
        reachable.any { it } -> "partial"
        else -> "unreachable"
    }
    suspend fun check(context: Context, target: String? = null): JSONObject {
        val targetOrigin = target?.let(::origin)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager.activeNetwork
        val caps = manager.getNetworkCapabilities(active)
        val link = active?.let(manager::getLinkProperties)
        // Network handle alone does not cover a VPN/DNS/proxy change on the same underlying link.
        val network = "$active|${link?.dnsServers}|${link?.httpProxy}|" +
            listOf(NetworkCapabilities.NET_CAPABILITY_VALIDATED, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL,
                NetworkCapabilities.NET_CAPABILITY_INTERNET).map { caps?.hasCapability(it) } +
            "|${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}"
        val origins = (references + listOfNotNull(targetOrigin)).distinct()
        val now = SystemClock.elapsedRealtime()
        val results = coroutineScope {
            origins.map { url -> async {
                val cached = synchronized(cache) { cache[url]?.takeIf { it.network == network && now - it.at in 0..30_000 } }
                if (cached != null) JSONObject(cached.value).put("cached", true).put("age_ms", now - cached.at)
                else {
                    val measured = if (active == null) JSONObject().put("origin", url).put("outcome", "offline").put("http_reachable", false)
                    else probe(url)
                    measured.put("checked_at_ms", System.currentTimeMillis())
                    synchronized(cache) {
                        cache[url] = Cached(network, SystemClock.elapsedRealtime(), measured.toString())
                        while (cache.size > 16) cache.remove(cache.keys.first())
                    }
                    measured.put("cached", false).put("age_ms", 0)
                }
            } }.awaitAll()
        }
        val international = results.filter { it.getString("origin") in references.drop(1) }
        return JSONObject().put("connected", active != null)
            .put("internet_capability", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            .put("system_validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("captive_portal", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true)
            .put("vpn_active", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            .put("proxy_configured", link?.httpProxy != null)
            .put("international_samples", sampleState(international.map { it.optBoolean("http_reachable") }))
            .put("target", targetOrigin?.let { t -> results.first { it.getString("origin") == t } } ?: JSONObject.NULL)
            .put("checks", JSONArray(results))
            .put("source", "Android native HTTPS HEAD; system DNS/proxy; no cookies or authorization")
            .put("decision_hint", "仅代表这些站点此刻的响应；有HTTP响应不等于页面/搜索可用，403/429是服务限制。checks已有目标域名时直接引用，不按域名重复检测。超时只能报告此刻未获得响应，不能说“典型被屏蔽”或认定必须VPN；DNS/路由/服务端均可能。VPN/proxy字段仅指系统可见配置，不能排除路由器代理。PRoot与WebView网络路径可能不同。目标不可达时不要反复开同类页面或自动打开系统浏览器；网络变化后再检查。")
    }
    private suspend fun probe(url: String): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val value = withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine<JSONObject> { continuation ->
                val call = client.newCall(Request.Builder().url(url).head().build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.success(JSONObject().put("outcome", failureKind(e)).put("http_reachable", false)))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val result = JSONObject().put("outcome", "http_response").put("http_reachable", true)
                                .put("http_status", it.code).put("http_success", it.isSuccessful)
                                .put("redirect", it.isRedirect)
                            if (continuation.isActive) continuation.resumeWith(Result.success(result))
                        }
                    }
                })
            }
        } ?: JSONObject().put("outcome", "timeout").put("http_reachable", false)
        return value.put("origin", url).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
    }
}
