package ai.capri.app.agent

import ai.capri.app.ModelRequests
import ai.capri.app.ModelConfig
import ai.capri.app.ModelHttpError
import ai.capri.app.PerformanceTrace
import ai.capri.app.model.ModelContextRejected
import ai.capri.app.model.ModelWire
import ai.capri.app.model.ModelApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** One cancellable transport for execution, compaction and final delivery; never retries tools. */
internal class ModelTransport(private val client: OkHttpClient, private val hydrate: (JSONArray) -> JSONArray = { it }) {
    companion object { private val admission=Semaphore(3) }
    private data class Timing(val trace: PerformanceTrace, val attempt: Int, val purpose: ModelRequests.Purpose, val queuedAt: Long)
    /** Application interceptor starts on the dispatcher worker, before DNS/connect work. */
    class QueueTiming : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            chain.request().tag(Timing::class.java)?.let { t ->
                t.trace.event(PerformanceTrace.Stage.MODEL_QUEUE, PerformanceTrace.elapsed(t.queuedAt), PerformanceTrace.Status.RETURNED, attempt = t.attempt, purpose = t.purpose)
            }
            return chain.proceed(chain.request())
        }
    }
    suspend fun request(config: ModelConfig, messages: JSONArray, tools: JSONArray, output: Int,
                        trace: PerformanceTrace, attempt: Int, finalOnly: Boolean, purpose: ModelRequests.Purpose = if (finalOnly) ModelRequests.Purpose.DELIVERY else ModelRequests.Purpose.EXECUTION): JSONObject = withContext(Dispatchers.IO) {
        val admittedAt=PerformanceTrace.now()
        admission.withPermit {
        trace.event(PerformanceTrace.Stage.MODEL_ADMISSION,PerformanceTrace.elapsed(admittedAt),PerformanceTrace.Status.RETURNED,attempt=attempt,purpose=purpose)
        val url = ModelWire.url(config).toString()
        require(url.startsWith("https://")) { "Model endpoint must use HTTPS." }
        val preparing = PerformanceTrace.now()
        var preparationStatus = PerformanceTrace.Status.ERROR
        val encoded = try {
            ModelWire.request(config, hydrate(messages), tools, output).apply { if (finalOnly) remove("tools") }.toString().also { preparationStatus = PerformanceTrace.Status.OK }
        } catch (cancel: kotlinx.coroutines.CancellationException) { preparationStatus = PerformanceTrace.Status.CANCELLED; throw cancel }
        finally { trace.event(PerformanceTrace.Stage.MODEL_PREPARE, PerformanceTrace.elapsed(preparing), preparationStatus, attempt = attempt, purpose = purpose) }
        val started = PerformanceTrace.now()
        val call = client.newCall(ModelApi.headers(config, Request.Builder().url(url))
            .tag(Timing::class.java, Timing(trace, attempt, purpose, started))
            .post(encoded.toRequestBody("application/json".toMediaType())).build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        trace.event(PerformanceTrace.Stage.MODEL_HEADERS, PerformanceTrace.elapsed(started), PerformanceTrace.Status.RETURNED, attempt = attempt, http = r.code, purpose = purpose)
                        val result = runCatching {
                            if (!r.isSuccessful) {
                                ModelContextRejected.parse(r.code, r.peekBody(16384).string())?.let { throw it }
                                throw ModelHttpError(r.code, r.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 600)?.times(1000))
                            }
                            val bodyStarted = PerformanceTrace.now()
                            var bodyStatus = PerformanceTrace.Status.ERROR
                            val raw = try { (r.body?.string() ?: error("Empty model response.")).also { bodyStatus = PerformanceTrace.Status.OK } }
                            finally { trace.event(PerformanceTrace.Stage.MODEL_BODY, PerformanceTrace.elapsed(bodyStarted), bodyStatus, attempt = attempt, purpose = purpose) }
                            val parseStarted = PerformanceTrace.now()
                            var parseStatus = PerformanceTrace.Status.ERROR
                            var usage: JSONObject? = null
                            try {
                                ModelWire.response(config, JSONObject(raw)).also {
                                    it.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                                    usage = it.optJSONObject("usage"); parseStatus = PerformanceTrace.Status.OK
                                }
                            } finally { trace.event(PerformanceTrace.Stage.MODEL_PARSE, PerformanceTrace.elapsed(parseStarted), parseStatus, attempt = attempt, usage = usage, purpose = purpose) }
                        }
                        if (continuation.isActive) continuation.resumeWith(result)
                    }
                }
            })
        }
        }
    }
}
