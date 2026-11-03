package ai.capri.app

/** Immutable request configuration; secret persistence belongs to the host. */
data class ModelConfig(val endpoint: String = "https://api.openai.com/v1", val model: String = "", val key: String = "", val protocol: String = "CHAT", val accountId: String = "", val contextWindow: Int? = null, val maxOutput: Int? = null, val contextSource: String = "models_api", val providerId: String = "", val modelName: String = "")
