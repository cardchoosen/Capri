package ai.capri.app.model

import ai.capri.app.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

/** Estimates include serialized tools and protocol overhead; they are not tokenizer counts. */
internal object ContextTokens {
    fun text(text: String): Long {
        var units = 0.0
        text.codePoints().forEach { cp -> units += when {
            cp < 128 -> 0.33
            cp in 0x3400..0x9fff || cp in 0xf900..0xfaff -> 1.0
            else -> Character.charCount(cp).toDouble()
        } }
        return ceil(units).toLong()
    }
    fun message(message: JSONObject): Long {
        message.optJSONObject("_capri_wire")?.optJSONArray("payload")?.let { return text(it.toString()) + 12 }
        val public = JSONObject(message.toString()); public.remove("_capri_scope")
        return text(public.toString()) + 12
    }
    fun estimate(messages: JSONArray, tools: JSONArray): Long = 16 + text(tools.toString()) +
        (0 until messages.length()).sumOf { message(messages.getJSONObject(it)) }
}

internal data class ContextBudget(
    val window: Int, val output: Int, val safety: Int, val source: String, val factor: Double = 1.15
) {
    val input: Int get() = window - output - safety
    fun tokens(rawEstimate: Long): Long = ceil(rawEstimate * factor).toLong()
    fun fits(rawEstimate: Long) = tokens(rawEstimate) <= input
    fun report(raw: Long) = JSONObject().put("context_window_tokens", window).put("input_budget_tokens", input)
        .put("estimated_input_tokens", tokens(raw)).put("output_tokens", output).put("safety_tokens", safety)
        .put("capacity_source", source).put("count_method", "estimate_calibrated_by_prompt_usage")
}

internal data class ModelCapacity(val window: Int, val maxOutput: Int?, val source: String) {
    fun budget(factor: Double = 1.15): ContextBudget {
        val output = minOf(maxOutput ?: 8192, 16384, window / 8).coerceAtLeast(1)
        val safety = maxOf(128, window / 20)
        return ContextBudget(window, output, safety, source, factor)
    }
}

/** Deliberately narrower than generic HTTP 400: an invalid parameter must never trigger compaction. */
internal class ModelContextRejected(val windowHint: Int?) : Exception("模型拒绝上下文长度") {
    companion object {
        fun parse(http: Int, raw: String): ModelContextRejected? {
            if (http !in setOf(400, 413, 422)) return null
            val error = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull() ?: return null
            val code = error.optString("code").lowercase()
            val message = error.optString("message").lowercase()
            val explicit = code in setOf("context_length_exceeded", "context_window_exceeded", "max_context_length_exceeded") ||
                (Regex("(maximum context length|context window|context length limit)").containsMatchIn(message) &&
                    Regex("(exceed|too long|maximum context length)").containsMatchIn(message))
            val anthropicOverflow = message.startsWith("prompt is too long")
            val geminiOverflow = message.contains("input token count") && message.contains("exceeds the maximum number of tokens")
            if (!explicit && !anthropicOverflow && !geminiOverflow) return null
            val hint = Regex("(?:maximum context length is|context (?:window|length limit)(?: is|:| of)?)\\s*([0-9][0-9,]*)", RegexOption.IGNORE_CASE)
                .find(message)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
                ?.takeIf { it in 1024..16_777_216 }?.toInt()
            val nativeHint = (Regex("([0-9][0-9,]*) maximum").find(message)?.groupValues?.get(1)
                ?: Regex("maximum number of tokens allowed \\(([0-9][0-9,]*)\\)").find(message)?.groupValues?.get(1))
            return ModelContextRejected(hint ?: nativeHint?.replace(",", "")?.toLongOrNull()?.takeIf { it in 1024..16_777_216 }?.toInt())
        }
    }
}

/** Account/endpoint/model scoped cache. Never persists credentials or provider error bodies. */
internal class ModelContexts(
    private val root: File,
    client: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val discover: (suspend (ModelConfig) -> ModelCapacity?)? = null
) {
    private val mutex = Mutex()
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS).build()
    private fun identity(config: ModelConfig): String = MessageDigest.getInstance("SHA-256")
        .digest((config.endpoint.trimEnd('/') + "\u0000" + config.model + "\u0000" + config.protocol + "\u0000" + config.key).toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun read(config: ModelConfig): JSONObject = runCatching {
        JSONObject(File(root, identity(config) + ".json").readText())
    }.getOrDefault(JSONObject())
    private fun save(config: ModelConfig, record: JSONObject) {
        root.mkdirs()
        val file = File(root, identity(config) + ".json")
        val temporary = File(root, file.name + ".tmp")
        temporary.writeText(record.toString())
        check(temporary.renameTo(file)) { "模型能力缓存写入失败" }
        root.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
            ?.drop(64)?.forEach { it.delete() }
    }
    suspend fun resolve(config: ModelConfig): ContextBudget = mutex.withLock {
        val record = read(config)
        if (config.contextWindow != null && record.optString("source") != "server_feedback" &&
            (record.optInt("window") != config.contextWindow || record.optInt("max_output") != (config.maxOutput ?: 0) || record.optString("source") != config.contextSource)) {
            record.put("window", config.contextWindow).put("max_output", config.maxOutput ?: JSONObject.NULL)
                .put("source", config.contextSource).put("expires", now() + 86_400_000)
            save(config, record)
        }
        if (record.optLong("expires") <= now()) {
            val capacity = try { discover?.invoke(config) ?: if (discover == null) {
                if (config.contextWindow != null) ModelCapacity(config.contextWindow, config.maxOutput, config.contextSource)
                else if (config.protocol == "CHAT" || config.protocol == "RESPONSES") metadata(config) else null
            } else null }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            val selected = capacity ?: official(config) ?: ModelCapacity(32768, null, "conservative_fallback")
            record.put("window", selected.window).put("max_output", selected.maxOutput ?: JSONObject.NULL)
                .put("source", selected.source).put("expires", now() + if (capacity != null) 86_400_000 else 300_000)
            save(config, record)
        }
        budget(record)
    }
    suspend fun observe(config: ModelConfig, rawEstimate: Long, promptTokens: Int) = mutex.withLock {
        if (rawEstimate <= 0 || promptTokens <= 0) return@withLock
        val record = read(config)
        if (!record.has("window")) return@withLock
        // Only increase conservatism; a short easy request cannot erase an earlier expensive-token sample.
        val factor = maxOf(record.optDouble("factor", 1.15), promptTokens.toDouble() / rawEstimate * 1.10).coerceAtMost(16.0)
        record.put("factor", factor); save(config, record)
    }
    suspend fun rejected(config: ModelConfig, attempted: ContextBudget, raw: Long, error: ModelContextRejected): ContextBudget = mutex.withLock {
        val record = read(config)
        // A lower explicit server window wins. Otherwise increase the token estimate, not an invented provider limit.
        if (error.windowHint != null && error.windowHint < attempted.window) {
            record.put("window", error.windowHint)
        } else {
            val factor = maxOf(attempted.factor * 1.5, attempted.input.toDouble() / raw.coerceAtLeast(1) * 1.35)
            if (factor > 16) throw ai.capri.app.knowledge.ContextBudgetFailure("无法可靠估算该模型的上下文；检查点已保留，请缩小本次输入。")
            record.put("factor", factor)
        }
        record.put("source", "server_feedback").put("expires", now() + 3_600_000)
        save(config, record)
        budget(record)
    }
    private fun budget(record: JSONObject): ContextBudget = ModelCapacity(record.getInt("window"),
        record.optInt("max_output").takeIf { it > 0 }, record.getString("source"))
        .budget(record.optDouble("factor", 1.15).takeIf { it.isFinite() && it in 1.0..16.0 } ?: 1.15)
    private fun official(config: ModelConfig): ModelCapacity? {
        val url = runCatching { config.endpoint.toHttpUrl() }.getOrNull() ?: return null
        return if (url.scheme == "https" && url.host == "api.deepseek.com" && url.port == 443 &&
            url.encodedPath.trimEnd('/') in setOf("", "/v1") && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            config.model in setOf("deepseek-flash", "deepseek-v4-pro")) ModelCapacity(1_048_576, 393216, "official_profile_2026_10") else null
    }
    private suspend fun metadata(config: ModelConfig): ModelCapacity? {
        val base = config.endpoint.trimEnd('/').toHttpUrl()
        require(base.scheme == "https" && base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
        val url = base.newBuilder().addPathSegment("models").build()
        val call = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${config.key}").build())
        val raw = suspendCancellableCoroutine<String?> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { r ->
                        if (!r.isSuccessful || r.body == null) return@use null
                        val source = r.body!!.source()
                        require(!source.request(1_048_577)) { "模型目录过大" }
                        source.readUtf8()
                    } }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        } ?: return null
        return parseMetadata(raw, config.model)
    }
    companion object {
        fun parseMetadata(raw: String, model: String): ModelCapacity? {
            val data = JSONObject(raw).optJSONArray("data") ?: return null
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                if (item.optString("id") != model) continue
                val window = positiveInt(item.opt("context_window")) ?: positiveInt(item.opt("context_length")) ?: return null
                val output = positiveInt(item.opt("max_output_tokens"))
                if (window < 1024) return null
                return ModelCapacity(window, output, "models_api")
            }
            return null
        }
        private fun positiveInt(value: Any?): Int? = (value as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 1.0..16_777_216.0 }?.toInt()
    }
}
