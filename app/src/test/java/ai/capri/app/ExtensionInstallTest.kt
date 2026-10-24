package ai.capri.app

import ai.capri.app.extensions.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ExtensionInstallTest {
    @Test fun installsConsumeOnlyWorkspaceSourcesAndCopyResourcesWithoutLinks() {
        val root = Files.createTempDirectory("capri-install").toFile()
        try {
            val workspace = Workspace(root.resolve("sessions"))
            val source = workspace.file("chat", "skill").apply { mkdirs() }
            source.resolve("SKILL.md").writeText("skill")
            source.resolve("references").mkdirs(); source.resolve("references/data.txt").writeText("data")
            assertEquals(source.canonicalFile, ExtensionInstall.source(workspace, "chat", "/workspace/skill"))
            for (path in listOf("../outside", "/etc/passwd", "missing")) assertTrue(runCatching { ExtensionInstall.source(workspace, "chat", path) }.isFailure)
            val copied = root.resolve("copied"); ExtensionInstall.copyDirectory(source, copied)
            assertEquals("data", copied.resolve("references/data.txt").readText())
            Files.createSymbolicLink(source.resolve("link").toPath(), root.toPath())
            assertTrue(runCatching { ExtensionInstall.copyDirectory(source, root.resolve("linked-copy")) }.isFailure)
            Files.delete(source.resolve("link").toPath())
            for (i in 1..501) source.resolve("file-$i").writeText("")
            assertTrue(runCatching { ExtensionInstall.copyDirectory(source, root.resolve("too-many")) }.isFailure)
        } finally { RuntimeFiles.delete(root) }
    }
    @Test fun copyStopsAtByteLimit() {
        val root = Files.createTempDirectory("capri-install-limit").toFile()
        try {
            val source = root.resolve("source").apply { mkdirs() }
            java.io.RandomAccessFile(source.resolve("large.bin"), "rw").use { it.setLength(ExtensionInstall.MAX_BYTES + 1) }
            assertTrue(runCatching { ExtensionInstall.copyDirectory(source, root.resolve("copy")) }.isFailure)
        } finally { root.deleteRecursively() }
    }
    @Test fun mcpFilesDefaultToEnabledButRejectCredentialsAndInvalidOptions() {
        val root = Files.createTempDirectory("capri-config").toFile()
        try {
            val file = root.resolve("mcp.json")
            val remote = JSONObject("""{"id":"sample","name":"Sample","type":"remote","url":"https://example.com/mcp"}""")
            file.writeText(remote.toString()); assertTrue(ExtensionInstall.mcpConfig(file).getBoolean("enabled"))
            for (bad in listOf(remote.toString().let { JSONObject(it).put("token", "not-accepted") }, JSONObject(remote.toString()).put("env", JSONObject()), JSONObject(remote.toString()).put("enabled", "true"))) {
                file.writeText(bad.toString()); assertTrue(runCatching { ExtensionInstall.mcpConfig(file) }.isFailure)
            }
            file.writeText("{"); assertTrue(runCatching { ExtensionInstall.mcpConfig(file) }.isFailure)
            val request = JSONObject("""{"action":"install_skill","path":"my-skill","replace":true}""")
            ExtensionInstall.validate(request)
            for (bad in listOf(JSONObject(request.toString()).put("replace", "true"), JSONObject(request.toString()).put("token", "no"), JSONObject().put("action", "install_mcp"))) assertTrue(runCatching { ExtensionInstall.validate(bad) }.isFailure)
        } finally { root.deleteRecursively() }
    }
}
