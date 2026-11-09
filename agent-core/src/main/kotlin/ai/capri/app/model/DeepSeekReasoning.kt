package ai.capri.app.model

import ai.capri.app.ModelConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object DeepSeekReasoning {
    private val models = setOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "deepseek-v4-pro")
    private val levels = listOf("default", "none", "low", "high", "max")

    fun options(config: ModelConfig): List<String> {
        if (config.model !in models) return emptyList()
        val url = config.endpoint.toHttpUrlOrNull() ?: return emptyList()
        if (!url.isHttps || url.host != "api.deepseek.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return emptyList()
        val paths = when (config.protocol) {
            "CHAT", "RESPONSES" -> setOf("", "/v1")
            "ANTHROPIC" -> setOf("/anthropic", "/anthropic/v1")
            else -> return emptyList()
        }
        return if (url.encodedPath.trimEnd('/') in paths) levels else emptyList()
    }
}
