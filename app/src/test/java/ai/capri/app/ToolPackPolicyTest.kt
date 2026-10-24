package ai.capri.app

import ai.capri.app.extensions.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ToolPackPolicyTest {
    @Test fun manifestsRequireVersionsOriginsAndStructuredMcpContracts() {
        val root = Files.createTempDirectory("capri-toolpack").toFile()
        try {
            val manifest = JSONObject("""{"schema_version":1,"id":"sample","name":"Sample","version":"1.0.0","source":"https://example.com/sample","mcp":{"type":"local","command":["python","${'$'}{extension}/server.py"]}}""")
            val file = root.resolve("toolpack.json")
            file.writeText(manifest.toString())
            assertEquals("pack-sample", ToolPackPolicy.config(ToolPackPolicy.manifest(root), true).getString("id"))
            val original = ToolPackPolicy.hash(root)
            root.resolve("server.py").writeText("print('new content')")
            assertNotEquals(original, ToolPackPolicy.hash(root))
            val variants = listOf(
                JSONObject(manifest.toString()).put("source", "http://example.com/source"),
                JSONObject(manifest.toString()).put("source", "https://user:secret@example.com/source"),
                JSONObject(manifest.toString()).put("version", ""),
                JSONObject(manifest.toString()).put("schema_version", 2),
                JSONObject(manifest.toString()).put("mcp", JSONObject().put("type", "local").put("command", "python server.py")),
                JSONObject(manifest.toString()).put("token", "no")
            )
            for (bad in variants) { file.writeText(bad.toString()); assertTrue(runCatching { ToolPackPolicy.manifest(root) }.isFailure) }
            assertTrue(runCatching { ToolPackPolicy.mcpId("a".repeat(60)) }.isFailure)
        } finally { root.deleteRecursively() }
    }
    @Test fun archivePathsCannotEscapeAndTemporaryExtractionIsRemoved() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("capri-pack-zip").toFile()
        val scratch = root.resolve("scratch").apply { mkdirs() }
        val zip = root.resolve("bad.zip")
        try {
            java.util.zip.ZipOutputStream(zip.outputStream()).use { output ->
                output.putNextEntry(java.util.zip.ZipEntry("../escape")); output.write("bad".toByteArray()); output.closeEntry()
            }
            assertTrue(runCatching { ToolPackPolicy.withSource(zip, scratch) { it.name } }.isFailure)
            assertFalse(root.resolve("escape").exists()); assertTrue(scratch.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

}
