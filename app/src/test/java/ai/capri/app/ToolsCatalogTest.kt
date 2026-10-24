package ai.capri.app

import ai.capri.app.extensions.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolsCatalogTest {
    private fun spec(name: String): JSONObject {
        val parameters = JSONObject().put("type", "object").put("required", JSONArray(listOf("query")))
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
        return JSONObject().put("function", JSONObject().put("name", name).put("description", "Search documentation").put("parameters", parameters))
    }
    @Test fun catalogUsesExactContractsAndFiltersBySource() {
        val builtins = JSONArray().put(spec("read_file")); val external = spec("mcp_abc")
        val tools = ToolsCatalog.entries(builtins, listOf(external), { "读取文件" }, { "MCP · docs / query" })
        assertEquals(listOf("read_file", "mcp_abc"), tools.map { it.name })
        assertEquals("MCP · docs", tools.last().source)
        assertEquals(external.getJSONObject("function").getJSONObject("parameters").toString(), tools.last().parameters.toString())
        assertEquals(1, ToolsCatalog.filter(tools, "", 1).size)
        assertEquals("mcp_abc", ToolsCatalog.filter(tools, "DOCS", 2).single().name)
        assertEquals(0, ToolsCatalog.filter(tools, "不存在", 0).size)
        external.getJSONObject("function").getJSONObject("parameters").put("modified", true)
        assertFalse(tools.last().parameters.has("modified"))
    }
    @Test fun removedPackageActionsAreNotAdvertisedAndPlainMcpRemains() {
        val schema = ExtensionInstall.specification().getJSONObject("function")
        assertFalse(schema.toString().contains("toolpack"))
        val parameters = schema.getJSONObject("parameters")
        val branches = parameters.getJSONArray("oneOf")
        assertEquals(3, branches.length())
        val actions = parameters.getJSONObject("properties").getJSONObject("action").getJSONArray("enum").toString()
        assertTrue(actions.contains("install_mcp")); assertTrue(actions.contains("install_skill"))
        val config = JSONObject("""{"id":"sample","name":"Sample","type":"remote","url":"https://example.com/mcp"}""")
        assertEquals("sample", ExtensionPolicy.config(config).getString("id"))
        assertTrue(runCatching { ExtensionPolicy.config(config.put("toolpack", JSONObject())) }.isFailure)
    }
}
