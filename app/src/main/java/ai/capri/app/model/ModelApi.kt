package ai.capri.app.model

import ai.capri.app.ModelConfig
import ai.capri.app.ModelHttpError
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class ModelApi(client: OkHttpClient = OkHttpClient(), private val catalog: ModelCatalog? = null) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    suspend fun models(account: ModelAccount): List<ModelOption> {
        val result = linkedMapOf<String, ModelOption>(); var cursor: String? = null
        repeat(4) {
            val c = account.config(ModelOption("discovery"))
            val url = account.endpoint.trimEnd('/').toHttpUrl().newBuilder().addPathSegment("models").apply {
                if (account.protocol == ModelProtocol.GEMINI) { addQueryParameter("pageSize", "100"); cursor?.let { addQueryParameter("pageToken", it) } }
                if (account.protocol == ModelProtocol.ANTHROPIC) { addQueryParameter("limit", "100"); cursor?.let { addQueryParameter("after_id", it) } }
            }.build()
            val response = get(c, url)
            parseModels(response, account.protocol).forEach { result[it.id] = it }
            require(result.size <= 2000) { "模型数量超过上限" }
            cursor = when (account.protocol) {
                ModelProtocol.GEMINI -> response.optString("nextPageToken").takeIf { it.isNotBlank() }
                ModelProtocol.ANTHROPIC -> response.optString("last_id").takeIf { response.optBoolean("has_more") && it.isNotBlank() }
                else -> null
            }
            if (cursor == null) return (catalog?.enrich(account, result.values.toList()) ?: result.values.toList()).sortedWith(compareBy<ModelOption> { it.context == null }.thenBy { it.name.lowercase() })
        }
        error("模型目录过大，未保存不完整的模型列表；可手动添加模型")
    }
    suspend fun balance(account: ModelAccount): String {
        val c = account.config(ModelOption("balance")).copy(protocol = "CHAT")
        require(ai.capri.app.retrieval.ProviderCapabilities.deepSeek(c)) { "该配置不支持官方DeepSeek余额查询" }
        val url = account.endpoint.trimEnd('/').toHttpUrl().newBuilder().apply {
            encodedPath("/user/balance")
        }.build()
        val response = get(c, url); val balances = response.getJSONArray("balance_infos")
        require(balances.length() > 0) { "账户未返回余额" }
        return (0 until balances.length()).joinToString(" · ") { i -> val b = balances.getJSONObject(i)
            "${b.getString("currency")} ${b.getString("total_balance")}" } + if (response.optBoolean("is_available")) "" else " · 当前不可用"
    }
    private suspend fun get(c: ModelConfig, url: HttpUrl): JSONObject {
        val call = http.newCall(headers(c, Request.Builder().url(url)).get().build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { r ->
                        if (!r.isSuccessful) throw ModelHttpError(r.code)
                        val source = r.body?.source() ?: error("空响应")
                        require(!source.request(2_097_153)) { "模型接口响应过大" }
                        JSONObject(source.readUtf8())
                    } }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }
    companion object {
        fun headers(c: ModelConfig, builder: Request.Builder): Request.Builder = builder.apply {
            when (ModelProtocol.valueOf(c.protocol)) {
                ModelProtocol.ANTHROPIC -> {
                    // Use the current official API; retain the legacy header for compatible third-party endpoints.
                    if (c.endpoint.toHttpUrl().host == "api.anthropic.com") { header("Authorization", "Bearer ${c.key}"); header("anthropic-version", "2026-10-21") }
                    else { header("x-api-key", c.key); header("anthropic-version", "2023-06-01") }
                }
                ModelProtocol.GEMINI -> header("x-goog-api-key", c.key)
                else -> header("Authorization", "Bearer ${c.key}")
            }
        }
        fun parseModels(raw: JSONObject, protocol: ModelProtocol): List<ModelOption> {
            val a = raw.optJSONArray(if (protocol == ModelProtocol.GEMINI) "models" else "data") ?: error("接口未返回模型列表，可手动添加模型ID")
            return (0 until a.length()).mapNotNull { i ->
                val m = a.getJSONObject(i)
                if (protocol == ModelProtocol.GEMINI && m.has("supportedGenerationMethods") &&
                    !(0 until m.getJSONArray("supportedGenerationMethods").length()).any { m.getJSONArray("supportedGenerationMethods").getString(it) == "generateContent" }) return@mapNotNull null
                m.optJSONObject("architecture")?.optJSONArray("output_modalities")?.let { output ->
                    if (!(0 until output.length()).any { output.optString(it) == "text" }) return@mapNotNull null
                }
                val id = if (protocol == ModelProtocol.GEMINI) m.getString("name").removePrefix("models/") else m.getString("id")
                require(id.isNotBlank() && id.length <= 200 && id.none(Char::isISOControl)) { "无效模型ID" }
                fun number(vararg names: String): Int? = names.firstNotNullOfOrNull { n ->
                    (m.opt(n) as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 1.0..16_777_216.0 }?.toInt()
                }
                val inputLimit = number("inputTokenLimit")
                val outputLimit = number("max_output_tokens", "outputTokenLimit", "max_tokens") ?: m.optJSONObject("top_provider")?.optInt("max_completion_tokens")?.takeIf { it > 0 }
                // Gemini exposes input and output separately. Display the input limit; use it conservatively as a total budget.
                ModelOption(id, m.optString("display_name", m.optString("displayName", m.optString("name", id))).take(160),
                    number("context_window", "context_length", "max_input_tokens") ?: inputLimit, outputLimit)
            }.distinctBy { it.id }
        }
        fun errorLabel(error: Throwable, listing: Boolean = true): String = when (error) {
            is ModelHttpError -> when (error.code) { 401, 403 -> "API Key无效、权限不足或区域不匹配"; 404, 405 -> if (listing) "该接口不支持获取列表，可手动添加模型" else "余额接口不可用"; 429 -> "请求频率受限，请稍后刷新"; else -> "服务返回 HTTP ${error.code}" }
            is IOException -> "网络连接失败，请稍后刷新"
            else -> "未能获取有效数据"
        }
    }
}
