package ai.capri.app.git

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fixed GitHub endpoint; no credential forwarding to custom hosts or redirects. */
internal object GitHubAccountResolver {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    suspend fun username(rawToken: String, http: OkHttpClient = client): String {
        val token = GitCredentialPolicy.token(rawToken)
        val call = http.newCall(Request.Builder().url("https://api.github.com/user")
            .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Capri-Android").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalArgumentException("无法连接 GitHub，请检查网络后重试；原账号未修改"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val login = response.use {
                            require(it.code == 200) {
                                when (it.code) {
                                    401 -> "GitHub Token 无效或已过期，原账号未修改"
                                    403, 429 -> "GitHub 拒绝访问或请求受限，请检查 Token 或稍后重试；原账号未修改"
                                    else -> "GitHub 账号识别失败（HTTP ${it.code}），原账号未修改"
                                }
                            }
                            val body = it.body ?: throw IllegalArgumentException("GitHub 响应无效，原账号未修改")
                            val bytes = java.io.ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(bytes.size() + count <= 16384) { "GitHub 响应过大，原账号未修改" }
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            val value = JSONObject(bytes.toString("UTF-8")).opt("login")
                            require(value is String && value.isNotBlank() && value.none { c -> c.isWhitespace() }) { "GitHub 未返回有效账号，原账号未修改" }
                            GitCredentialPolicy.username(value)
                        }
                        if (continuation.isActive) continuation.resume(login)
                    } catch (error: Exception) {
                        val message = if (error is IllegalArgumentException) error.message else "GitHub 响应无效，原账号未修改"
                        if (continuation.isActive) continuation.resumeWithException(IllegalArgumentException(message))
                    }
                }
            })
        }
    }
}
