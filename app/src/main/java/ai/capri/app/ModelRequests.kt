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
internal class ModelRequestFailure(val category: String, cause: Exception, val budgetExhausted: Boolean = false) : Exception(category, cause)

/** Retries only the model transport. The caller's tool dispatcher is never retried here. */
internal class ModelRequests(
    private val maximum: Int = 50,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val reservedDelivery: Int = 0
) {
    enum class Purpose { EXECUTION, COMPRESSION, DELIVERY }
    private val counts = Purpose.entries.associateWith { 0 }.toMutableMap()
    private var retries = 0
    fun report(): JSONObject = JSONObject().put("maximum", maximum).put("used", used).put("remaining", (maximum - used).coerceAtLeast(0))
        .put("execution_remaining", (maximum - used - reservedDelivery).coerceAtLeast(0)).put("reserved_delivery", reservedDelivery).put("retries", retries)
        .put("attempts_by_purpose", JSONObject(counts.mapKeys { it.key.name.lowercase() }))
    fun message(): JSONObject = JSONObject().put("role", "system").put("content", "Client request budget (actual attempts; retries are included in purpose totals): " + report() + "\nReserve delivery capacity. Reuse evidence; avoid starting work that cannot be verified within the remaining budget. If unfinished, preserve state and report unresolved work; never claim completion to satisfy a budget.")
    var used = 0
        private set
    suspend fun execute(purpose: Purpose = Purpose.EXECUTION, onRetry: (Int, String) -> Unit = { _, _ -> }, onBackoff: (Long) -> Unit = {}, block: suspend () -> JSONObject): JSONObject {
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            if (used >= maximum - if (purpose == Purpose.DELIVERY) 0 else reservedDelivery) throw ModelRequestFailure("本轮模型请求预算已用尽", IllegalStateException(), budgetExhausted = true)
            used++; counts[purpose] = counts.getValue(purpose) + 1
            if (attempt > 0) retries++
            try { return block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (input: InputAttachmentFailure) { throw input }
            catch (context: ai.capri.app.model.ModelContextRejected) { throw context }
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
                if (!retryable || attempt == 2 || used >= maximum - (if (purpose == Purpose.DELIVERY) 0 else reservedDelivery)) throw ModelRequestFailure(category, error)
                val requested = (error as? ModelHttpError)?.retryAfterMs
                // Do not retry sooner than a server's long Retry-After. Leave explicit continuation to the user.
                if (requested != null && requested > 5000) throw ModelRequestFailure(category, error)
                onRetry(attempt + 1, category)
                val backoffStarted = System.nanoTime()
                try { pause(maxOf(500L shl attempt, requested ?: 0)) }
                finally { onBackoff(((System.nanoTime() - backoffStarted) / 1_000_000).coerceAtLeast(0)) }
            }
        }
        error("unreachable")
    }
}
