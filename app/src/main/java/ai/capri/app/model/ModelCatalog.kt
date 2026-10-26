package ai.capri.app.model

import org.json.JSONObject

/** Small, dated offline metadata snapshot. Never grants a proxy the official vendor's limits. */
internal class ModelCatalog(raw: String) {
    private val providers = JSONObject(raw).getJSONObject("providers")
    fun enrich(account: ModelAccount, models: List<ModelOption>): List<ModelOption> {
        val preset = Providers.get(account.provider)
        if (preset.id == "custom" || account.endpoint.trimEnd('/') != preset.url.trimEnd('/')) return models
        val known = providers.optJSONObject(account.provider) ?: return models
        return models.map { model ->
            if (model.context != null) model else {
                val record = known.optJSONObject(model.id) ?: return@map model
                val context = record.optInt("context").takeIf { it in 1024..16_777_216 } ?: return@map model
                model.copy(context = context, output = model.output ?: record.optInt("output").takeIf { it > 0 }, source = "models_registry")
            }
        }
    }
}
