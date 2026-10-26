package ai.capri.app.model

import ai.capri.app.ModelConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class ModelProtocol(val label: String) { CHAT("Chat Completions"), RESPONSES("Responses"), ANTHROPIC("Anthropic Messages"), GEMINI("Gemini") }
internal data class Provider(val id: String, val name: String, val url: String, val protocol: ModelProtocol = ModelProtocol.CHAT, val icon: String = id)
internal object Providers {
    val all = listOf(
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1"),
        Provider("qwen", "阿里云百炼 · Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        Provider("kimi", "Kimi", "https://api.moonshot.cn/v1"),
        Provider("zhipu", "智谱 · GLM", "https://open.bigmodel.cn/api/paas/v4"),
        Provider("minimax", "MiniMax", "https://api.minimax.cn/v1"),
        Provider("openai", "OpenAI", "https://api.openai.com/v1", ModelProtocol.RESPONSES),
        Provider("claude", "Anthropic · Claude", "https://api.anthropic.com/v1", ModelProtocol.ANTHROPIC),
        Provider("gemini", "Google · Gemini", "https://generativelanguage.googleapis.com/v1beta", ModelProtocol.GEMINI),
        Provider("grok", "xAI · Grok", "https://api.x.ai/v1"),
        Provider("siliconcloud", "硅基流动", "https://api.siliconflow.cn/v1"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
        Provider("custom", "自定义接口", "")
    )
    fun get(id: String) = all.firstOrNull { it.id == id } ?: all.last()
    fun detect(url: String) = all.firstOrNull { it.url.isNotBlank() && it.url.trimEnd('/') == url.trimEnd('/') } ?: all.last()
    fun endpoint(raw: String): String {
        val url = raw.trim().trimEnd('/').toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "请填写不含凭据或查询参数的 HTTPS Base URL" }
        return url.toString().trimEnd('/')
    }
    fun logo(provider: String, model: String): String {
        if (provider !in setOf("custom", "openrouter", "siliconcloud")) return provider
        val id = model.lowercase()
        return when {
            id.startsWith("deepseek") -> "deepseek"
            id.startsWith("qwen") -> "qwen"
            id.startsWith("moonshot") || id.startsWith("kimi") -> "kimi"
            id.startsWith("z-ai/") || id.startsWith("thudm/") || id.startsWith("glm") -> "zhipu"
            id.startsWith("minimax") -> "minimax"
            id.startsWith("openai/") || id.startsWith("gpt-") -> "openai"
            id.startsWith("anthropic/") || id.startsWith("claude-") -> "claude"
            id.startsWith("google/") || id.startsWith("gemini-") -> "gemini"
            id.startsWith("x-ai/") || id.startsWith("grok-") -> "grok"
            else -> provider
        }
    }
    fun contextLabel(tokens: Int?): String {
        if (tokens == null || tokens <= 0) return "上下文未知"
        fun number(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, value)
        return when { tokens >= 1_000_000 -> number(tokens / if (tokens % 1_048_576 == 0) 1_048_576.0 else 1_000_000.0) + "M"
            tokens >= 1000 -> number(tokens / if (tokens % 1024 == 0) 1024.0 else 1000.0) + "K"
            else -> tokens.toString() }
    }
}
internal data class ModelOption(val id: String, val name: String = id, val context: Int? = null, val output: Int? = null, val manual: Boolean = false, val source: String = "models_api") {
    fun json() = JSONObject().put("id", id).put("name", name).put("context", context ?: JSONObject.NULL).put("output", output ?: JSONObject.NULL).put("manual", manual).put("source", source)
    companion object {
        fun parse(o: JSONObject) = ModelOption(o.getString("id"), o.optString("name", o.getString("id")), o.optInt("context").takeIf { it > 0 }, o.optInt("output").takeIf { it > 0 }, o.optBoolean("manual"), o.optString("source", "models_api"))
    }
}
internal data class ModelAccount(val id: String = UUID.randomUUID().toString(), val provider: String, val name: String,
    val endpoint: String, val key: String, val protocol: ModelProtocol, val models: List<ModelOption> = emptyList()) {
    fun config(model: ModelOption) = ModelConfig(endpoint, model.id, key, protocol.name, id, model.context, model.output, model.source, provider, model.name)
    fun json() = JSONObject().put("id", id).put("provider", provider).put("name", name).put("endpoint", endpoint).put("key", key)
        .put("protocol", protocol.name).put("models", JSONArray(models.map { it.json() }))
    companion object {
        fun parse(o: JSONObject) = ModelAccount(o.getString("id"), o.getString("provider"), o.getString("name"), o.getString("endpoint"), o.getString("key"),
            ModelProtocol.valueOf(o.getString("protocol")), o.getJSONArray("models").let { a -> (0 until a.length()).map { ModelOption.parse(a.getJSONObject(it)) } })
    }
}

/** Storage callback encrypts the complete document. Selections use account IDs, never secret copies. */
internal class ModelAccounts(private val read: () -> String?, private val write: (String) -> Unit, private val legacy: () -> ModelConfig, private val decorate: (ModelAccount) -> ModelAccount = { it }) {
    private var state: JSONObject? = null
    private var cachedAccounts: List<ModelAccount>? = null
    @Synchronized private fun state(): JSONObject {
        state?.let { return it }
        val saved = read()
        val data = if (saved != null) JSONObject(saved) else JSONObject().put("accounts", JSONArray()).put("selections", JSONObject())
        if (saved == null) {
            val old = legacy()
            if (old.key.isNotBlank()) {
                val provider = Providers.detect(old.endpoint)
                // Existing configuration used Chat Completions, even for an OpenAI endpoint.
                val account = ModelAccount(provider = provider.id, name = provider.name, endpoint = old.endpoint, key = old.key,
                    protocol = ModelProtocol.CHAT, models = old.model.takeIf { it.isNotBlank() }?.let { listOf(ModelOption(it, manual = true)) } ?: emptyList())
                data.getJSONArray("accounts").put(decorate(account).json()); data.put("default", account.id)
            }
            write(data.toString())
        }
        state = data
        return data
    }
    @Synchronized fun all(): List<ModelAccount> {
        cachedAccounts?.let { return it }
        return state().getJSONArray("accounts").let { a -> (0 until a.length()).map { ModelAccount.parse(a.getJSONObject(it)) } }.also { cachedAccounts = it }
    }
    @Synchronized fun save(account: ModelAccount) {
        require(account.name.isNotBlank() && account.name.length <= 80 && account.key.isNotBlank() && account.key.length <= 8192 && account.key.none { it == '\n' || it == '\r' }) { "请填写名称和有效 API Key" }
        require(account.models.size <= 2000 && account.models.all { it.id.isNotBlank() && it.id.length <= 200 && it.id.none(Char::isISOControl) }) { "模型列表无效" }
        val normalized = decorate(account.copy(endpoint = Providers.endpoint(account.endpoint)))
        val data = JSONObject(state().toString())
        val accounts = all().toMutableList(); val index = accounts.indexOfFirst { it.id == account.id }
        if (index < 0) { require(accounts.size < 40) { "最多保存40个API配置" }; accounts += normalized } else accounts[index] = normalized
        data.put("accounts", JSONArray(accounts.map { it.json() }))
        if (!data.has("default")) data.put("default", account.id)
        commit(data)
    }
    @Synchronized fun remove(id: String) {
        val data = JSONObject(state().toString()); val remaining = all().filterNot { it.id == id }
        data.put("accounts", JSONArray(remaining.map { it.json() }))
        if (data.optString("default") == id) data.put("default", remaining.firstOrNull()?.id ?: "")
        // Keep dangling selections explicit: never silently move an existing chat to another account.
        commit(data)
    }
    @Synchronized fun selected(chat: String? = null): Pair<ModelAccount, ModelOption>? {
        val selection = chat?.let { state().getJSONObject("selections").optJSONObject(it) }
        val id = selection?.optString("account") ?: state().optString("default")
        val account = all().firstOrNull { it.id == id } ?: return null
        val model = if (selection == null) account.models.firstOrNull() else account.models.firstOrNull { it.id == selection.optString("model") }
        return model?.let { account to it }
    }
    @Synchronized fun select(chat: String, account: String, model: String) {
        require(all().any { it.id == account && it.models.any { m -> m.id == model } }) { "模型或API配置已删除，请重新选择" }
        val data = JSONObject(state().toString()); data.getJSONObject("selections").put(chat, JSONObject().put("account", account).put("model", model)); commit(data)
    }
    fun config(chat: String? = null): ModelConfig = selected(chat)?.let { it.first.config(it.second) } ?: ModelConfig()
    private fun commit(data: JSONObject) { write(data.toString()); state = data; cachedAccounts = null }
}
