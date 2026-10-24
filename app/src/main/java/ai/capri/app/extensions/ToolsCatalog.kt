package ai.capri.app.extensions

import org.json.JSONArray
import org.json.JSONObject

/** A view of the actual model contracts, not a second registry or execution mechanism. */
internal data class CatalogTool(val name: String, val title: String, val source: String, val mcp: Boolean, val description: String, val parameters: JSONObject)
internal object ToolsCatalog {
    fun entries(builtins: JSONArray, mcp: List<JSONObject>, title: (String) -> String, mcpTitle: (String) -> String): List<CatalogTool> {
        fun entry(spec: JSONObject, remote: Boolean): CatalogTool {
            val f = spec.getJSONObject("function"); val name = f.getString("name")
            return CatalogTool(name, if (remote) mcpTitle(name) else title(name), if (remote) mcpTitle(name).substringBefore(" / ") else "Capri 内置", remote,
                f.optString("description"), JSONObject(f.getJSONObject("parameters").toString()))
        }
        return (0 until builtins.length()).map { entry(builtins.getJSONObject(it), false) } + mcp.map { entry(it, true) }
    }
    fun filter(tools: List<CatalogTool>, query: String): List<CatalogTool> = tools.filter {
            (query.isBlank() || "${it.name} ${it.title} ${it.source} ${it.description}".contains(query.trim(), ignoreCase = true))
    }
}
