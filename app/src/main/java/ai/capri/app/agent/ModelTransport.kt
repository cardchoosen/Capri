package ai.capri.app.agent

import ai.capri.app.ModelConfig
import ai.capri.app.ModelHttpError
import ai.capri.app.PerformanceTrace
import ai.capri.app.model.ModelContextRejected
import ai.capri.app.model.ModelWire
import ai.capri.app.model.ModelApi
import kotlinx.coroutines.Dispatchers
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
    suspend fun request(config: ModelConfig, messages: JSONArray, tools: JSONArray, output: Int,
                        trace: PerformanceTrace, attempt: Int, finalOnly: Boolean): JSONObject = withContext(Dispatchers.IO) {
        val url = ModelWire.url(config).toString()
        require(url.startsWith("https://")) { "Model endpoint must use HTTPS." }
        val body = ModelWire.request(config, hydrate(messages), tools, output).apply { if (finalOnly) remove("tools") }
        val started = PerformanceTrace.now()
        val call = client.newCall(ModelApi.headers(config, Request.Builder().url(url))
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        trace.event(PerformanceTrace.Stage.MODEL_HEADERS, PerformanceTrace.elapsed(started), PerformanceTrace.Status.RETURNED, attempt = attempt, http = r.code)
                        val result = runCatching {
                            if (!r.isSuccessful) {
                                ModelContextRejected.parse(r.code, r.peekBody(16384).string())?.let { throw it }
                                throw ModelHttpError(r.code, r.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 600)?.times(1000))
                            }
                            val bodyStarted = PerformanceTrace.now()
                            var bodyStatus = PerformanceTrace.Status.ERROR
                            val raw = try { (r.body?.string() ?: error("Empty model response.")).also { bodyStatus = PerformanceTrace.Status.OK } }
                            finally { trace.event(PerformanceTrace.Stage.MODEL_BODY, PerformanceTrace.elapsed(bodyStarted), bodyStatus, attempt = attempt) }
                            val parseStarted = PerformanceTrace.now()
                            var parseStatus = PerformanceTrace.Status.ERROR
                            var usage: JSONObject? = null
                            try {
                                ModelWire.response(config, JSONObject(raw)).also {
                                    it.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                                    usage = it.optJSONObject("usage"); parseStatus = PerformanceTrace.Status.OK
                                }
                            } finally { trace.event(PerformanceTrace.Stage.MODEL_PARSE, PerformanceTrace.elapsed(parseStarted), parseStatus, attempt = attempt, usage = usage) }
                        }
                        if (continuation.isActive) continuation.resumeWith(result)
                    }
                }
            })
        }
    }
}
