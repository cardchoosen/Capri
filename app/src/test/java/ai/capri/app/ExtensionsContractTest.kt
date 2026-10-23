package ai.capri.app

import ai.capri.app.extensions.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ExtensionsContractTest {
    @Test fun configsKeepCredentialsOutOfUrlsAndCommandsStructured() {
        fun remote(url: String) = JSONObject().put("id", "test").put("name", "Test").put("type", "remote").put("url", url)
        assertEquals("test", ExtensionPolicy.config(remote("https://example.com/mcp")).getString("id"))
        for (url in listOf("http://example.com/mcp", "https://u:p@example.com/mcp", "https://example.com/mcp?token=x")) assertTrue(runCatching { ExtensionPolicy.config(remote(url)) }.isFailure)
        assertTrue(runCatching { ExtensionPolicy.config(remote("https://attacker.test/mcp").put("credential", "github-account")) }.isFailure)
        assertTrue(runCatching { ExtensionPolicy.config(remote("https://example.com/mcp").put("headers", JSONObject().put("Authorization", "secret"))) }.isFailure)
        assertTrue(runCatching { ExtensionPolicy.config(remote("https://example.com/mcp").put("timeout_seconds", 2)) }.isFailure)
        assertTrue(runCatching { ExtensionPolicy.config(JSONObject("""{"id":"x","name":"x","type":"local","command":"python -m server"}""")) }.isFailure)
    }
    @Test fun skillYamlIsSafeAndDirectoriesCannotEscape() {
        val root = Files.createTempDirectory("capri-skills").toFile()
        try {
            val dir = root.resolve("sample").apply { mkdirs() }
            val source = dir.resolve("SKILL.md")
            source.writeText("---\nname: sample\ndescription: Test useful task\n---\nDo something")
            assertEquals("sample", SkillParser.parse(dir).id)
            for (bad in listOf("---\nname: other\ndescription: Test\n---\n", "---\nname: sample\nname: duplicate\ndescription: Test\n---\n", "---\nname: sample\ndescription: !!java.lang.ProcessBuilder {}\n---\n")) {
                source.writeText(bad); assertTrue(runCatching { SkillParser.parse(dir) }.isFailure)
            }
            assertTrue(runCatching { ExtensionPolicy.relative(root, "../outside") }.isFailure)
            assertTrue(runCatching { ExtensionPolicy.relative(root, "/absolute") }.isFailure)
            assertTrue(runCatching { ExtensionPolicy.relative(root, "a\\..\\outside") }.isFailure)
            assertEquals(root.resolve("refs/doc.md").canonicalPath, ExtensionPolicy.relative(root, "refs/doc.md").path)
        } finally { root.deleteRecursively() }
    }
    @Test fun rawSchemasSurviveSdkNarrowingAndStreamingChunks() {
        val schemas = mutableMapOf<String, JSONObject>()
        val reply = JSONObject("""{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"sample","inputSchema":{"type":"object","additionalProperties":false,"properties":{"count":{"type":"integer","minimum":1}},"required":["count"]}}]}}""")
        val raw = (reply.toString() + "\n").toByteArray()
        val observer = SchemaCapture(schemas)
        raw.toList().chunked(7).forEach { block -> val bytes = block.toByteArray(); observer.accept(bytes, 0, bytes.size) }
        assertFalse(schemas.getValue("sample").getBoolean("additionalProperties"))
        val httpSchemas = mutableMapOf<String, JSONObject>(); val http = SchemaCapture(httpSchemas, true)
        val pretty = reply.toString(2).toByteArray(); http.accept(pretty, 0, pretty.size); http.flush()
        assertEquals(schemas.getValue("sample").toString(), httpSchemas.getValue("sample").toString())
        val sseSchemas = mutableMapOf<String, JSONObject>(); val sse = SchemaCapture(sseSchemas)
        val event = ("data: " + reply.toString() + "\n\n").toByteArray(); sse.accept(event, 0, event.size)
        assertTrue(sseSchemas.containsKey("sample"))
    }
    @Test fun toolNamesAreStableDistinctAndModelCompatible() {
        val first = ExtensionManager.toolName("my-server", "odd/name:tool")
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        assertEquals(first, ExtensionManager.toolName("my-server", "odd/name:tool"))
        assertNotEquals(first, ExtensionManager.toolName("another-server", "odd/name:tool"))
        assertNotEquals(first, ExtensionManager.toolName("my-server", "different"))
    }
}
