package ai.capri.app.retrieval

import ai.capri.app.ModelConfig
import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Provider capabilities are distinct from model names and from the user's per-feature preference. */
internal object ProviderCapabilities {
    const val DEEPSEEK_WEB = "deepseek-web"
    fun deepSeek(config: ModelConfig): Boolean {
        val url = config.endpoint.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host == "api.deepseek.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null && url.encodedPath.trimEnd('/') in setOf("", "/v1") && config.key.isNotBlank()
    }
}
internal class Enhancements(context: Context) {
    private val preferences = context.getSharedPreferences("enhancements", Context.MODE_PRIVATE)
    fun enabled(id: String): Boolean = preferences.getBoolean(id, false)
    fun enable(id: String, enabled: Boolean) { require(id == ProviderCapabilities.DEEPSEEK_WEB); preferences.edit().putBoolean(id, enabled).apply() }
    @Volatile var deepSeekStatus: String = "尚未调用；账户可用性在实际使用时确认"
        private set
    fun record(status: String) { deepSeekStatus = status }
}
