package ai.capri.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

class ModelHttpError(val code: Int, val retryAfterMs: Long? = null) : Exception("HTTP $code")
class ModelRequestFailure(val category: String, cause: Exception, val budgetExhausted: Boolean = false) : Exception(category, cause)

/** Retries only the model transport. The caller's tool dispatcher is never retried here. */
class ModelRequests(
    private val maximum: Int = 50,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val reservedDelivery: Int = 0,
    private val purposeLimits: Map<Purpose,Int> = mapOf(Purpose.COMPRESSION to 6,Purpose.DELIVERY to 3,Purpose.DELEGATION to 8),
    private val now:()->Long = { System.nanoTime()/1_000_000 },
    private val retryWindowMs:Long = 30_000
) {
    enum class Purpose { EXECUTION, COMPRESSION, DELIVERY, DELEGATION }
    private val counts = Purpose.entries.associateWith { 0 }.toMutableMap()
    private var retries = 0
    private val failures = Purpose.entries.associateWith { 0 }.toMutableMap()
    init { require(maximum>0 && reservedDelivery in 0 until maximum && purposeLimits.values.all { it>0 } && retryWindowMs>0) }
    @Synchronized private fun failed(purpose:Purpose) { failures[purpose]=failures.getValue(purpose)+1 }
    @Synchronized fun report(): JSONObject = JSONObject().put("maximum", maximum).put("used", used).put("remaining", (maximum - used).coerceAtLeast(0))
        .put("delegation_remaining",(maximum-used-reservedDelivery-2).coerceAtLeast(0)).put("execution_remaining", (maximum - used - reservedDelivery).coerceAtLeast(0)).put("reserved_delivery", reservedDelivery).put("retries", retries)
        .put("attempts_by_purpose", JSONObject(counts.mapKeys { it.key.name.lowercase() }))
        .put("failures_by_purpose",JSONObject(failures.mapKeys { it.key.name.lowercase() }))
        .put("purpose_limits",JSONObject(purposeLimits.mapKeys { it.key.name.lowercase() })).put("fallback_policy","same_config_only")
    fun message(): JSONObject = JSONObject().put("role", "system").put("content", "Client request budget (actual attempts; retries are included in purpose totals): " + report() + "\nReserve delivery capacity. Reuse evidence; avoid starting work that cannot be verified within the remaining budget. If unfinished, preserve state and report unresolved work; never claim completion to satisfy a budget.")
    @Volatile var used = 0
        private set
    @Synchronized private fun reserve(purpose: Purpose,retry: Boolean,before: (Int)->Unit) {
        if(counts.getValue(purpose)>=(purposeLimits[purpose]?:maximum))throw ModelRequestFailure("Model purpose budget exhausted: ${purpose.name.lowercase()}.",IllegalStateException(),budgetExhausted=true)
        val keep=if(purpose==Purpose.DELIVERY)0 else reservedDelivery + if(purpose==Purpose.DELEGATION)2 else 0
        if(used>=maximum-keep)throw ModelRequestFailure("Model request budget exhausted; parent reconciliation and delivery capacity are reserved.",IllegalStateException(),budgetExhausted=true)
        before(used+1)
        used++;counts[purpose]=counts.getValue(purpose)+1;if(retry)retries++
    }
    suspend fun execute(purpose: Purpose = Purpose.EXECUTION, onRetry: (Int, String) -> Unit = { _, _ -> }, onBackoff: (Long) -> Unit = {}, beforeAttempt: (Int)->Unit = {}, block: suspend () -> JSONObject): JSONObject {
        var attempt=0
        var deadline:Long?=null
        var lastFailure:ModelRequestFailure?=null
        while(true) {
            currentCoroutineContext().ensureActive()
            val remaining=deadline?.let{it-now()}
            if(remaining!=null && remaining<=0)throw checkNotNull(lastFailure)
            reserve(purpose,attempt>0,beforeAttempt)
            try {
                return if(remaining==null)block()
                else withTimeoutOrNull(remaining){block()}?:run{failed(purpose);throw checkNotNull(lastFailure)}
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure:ModelRequestFailure) { throw failure }
            catch (context: ai.capri.app.model.ModelContextRejected) { failed(purpose);throw context }
            catch (error: Exception) {
                if(error is ModelInputFailure)throw error
                failed(purpose)
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
                val failure=ModelRequestFailure(category,error);lastFailure=failure
                if(!retryable || used>=maximum-(if(purpose==Purpose.DELIVERY)0 else reservedDelivery+if(purpose==Purpose.DELEGATION)2 else 0) || counts.getValue(purpose)>=(purposeLimits[purpose]?:maximum))throw failure
                if(deadline==null)deadline=now()+retryWindowMs
                val remainingWait=deadline!!-now()
                val requested=(error as? ModelHttpError)?.retryAfterMs?:0L
                // A retry must fit the fixed recovery window and honor Retry-After.
                if(remainingWait<=0 || requested>remainingWait)throw failure
                val wait=minOf(maxOf(1000L shl attempt.coerceAtMost(3),requested),remainingWait)
                onRetry(attempt + 1, category)
                val backoffStarted = System.nanoTime()
                try { pause(wait) }
                finally { onBackoff(((System.nanoTime() - backoffStarted) / 1_000_000).coerceAtLeast(0)) }
                attempt++
            }
        }
    }
}

/** Platform adapters mark input hydration failures so transport never retries them. */
interface ModelInputFailure
