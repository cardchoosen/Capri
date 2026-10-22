package ai.capri.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

internal class ModelHttpError(val code: Int, val retryAfterMs: Long? = null) : Exception("HTTP $code")
internal class ModelRequestFailure(val category: String, cause: Exception) : Exception(category, cause)

/** Retries only the model transport. The caller's tool dispatcher is never retried here. */
internal class ModelRequests(
    private val maximum: Int = 50,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    var used = 0
        private set
    suspend fun execute(onRetry: (Int, String) -> Unit = { _, _ -> }, block: suspend () -> JSONObject): JSONObject {
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            if (used >= maximum) throw ModelRequestFailure("本轮模型请求预算已用尽", IllegalStateException())
            used++
            try { return block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                val category = when (error) {
                    is ModelHttpError -> "模型接口 HTTP ${error.code}"
                    is SSLException -> "模型 TLS 连接校验失败"
                    is ProtocolException -> "模型 HTTP 协议异常"
                    is java.net.UnknownHostException -> "模型域名解析失败"
                    is java.net.SocketTimeoutException -> "模型请求超时"
                    is IOException -> "模型连接中断或网络不可达"
                    else -> "模型响应格式异常"
                }
                val retryable = when (error) {
                    is ModelHttpError -> error.code in setOf(408, 429, 500, 502, 503, 504)
                    is SSLException, is ProtocolException -> false
                    is IOException -> true
                    else -> false
                }
                if (!retryable || attempt == 2 || used >= maximum) throw ModelRequestFailure(category, error)
                val requested = (error as? ModelHttpError)?.retryAfterMs
                // Do not retry sooner than a server's long Retry-After. Leave explicit continuation to the user.
                if (requested != null && requested > 5000) throw ModelRequestFailure(category, error)
                onRetry(attempt + 1, category)
                pause(maxOf(500L shl attempt, requested ?: 0))
            }
        }
        error("unreachable")
    }
}
