package ai.capri.host

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal fun integrationUrl(value: String): HttpUrl = value.toHttpUrl().also {
    require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    require(it.isHttps || it.host in setOf("127.0.0.1", "::1", "localhost")) {
        "A host integration must use HTTPS or loopback."
    }
}

internal object HostIntegrationHttp {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(35, TimeUnit.SECONDS).build()

    suspend fun <T> execute(request: Request, decode: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching { response.use(decode) })
            }
        })
    }
}
