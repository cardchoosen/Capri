package ai.capri.app.coding

import ai.capri.app.Workspace
import ai.capri.app.files.WorkspaceFileTools
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException

class CodingToolsTest {
    private fun fixture(block: (WorkspaceFileTools, File) -> Unit) {
        val directory = Files.createTempDirectory("capri-coding").toFile()
        try { block(WorkspaceFileTools(Workspace(directory)), File(directory, "chat/workspace").apply { mkdirs() }) }
        finally { directory.deleteRecursively() }
    }
    private fun call(tool: WorkspaceFileTools, name: String, vararg fields: Pair<String, Any>) = JSONObject(tool.execute("chat", name, JSONObject(mapOf(*fields))))
    private fun patch(body: String) = "*** Begin Patch\n$body\n*** End Patch"

    @Test fun projectFindsRootRulesManifestAndDeclaredCommandsWithoutExecuting() = fixture { tool, root ->
        File(root, "AGENTS.md").writeText("Workspace convention.")
        val repo = File(root, "repo").apply { mkdir() }; File(repo, "AGENTS.md").writeText("Repository convention.")
        File(repo, "package.json").writeText("""{"packageManager":"pnpm@9.1.0","scripts":{"test":"touch should-not-run","build":"tsc"}}""")
        File(repo, "src").mkdir(); File(repo, "src/main.ts").writeText("export const value = 1\n")
        File(repo, "node_modules").mkdir(); File(repo, "node_modules/noise.ts").writeText("noise")
        File(repo, "src/AGENTS.md").writeText("Scoped source convention.")
        val result = call(tool, "project", "path" to "/workspace/repo/src/main.ts")
        assertEquals("ok", result.getString("status")); assertEquals("repo", result.getString("root"))
        assertEquals("pnpm", result.getString("package_manager")); assertEquals("pnpm", result.getJSONArray("declared_scripts").getJSONObject(0).getJSONArray("argv").getString(0))
        assertEquals(3, result.getJSONArray("project_rules").length()); assertEquals(1, result.getJSONObject("languages").getInt("TypeScript"))
        assertFalse(result.toString().contains("noise.ts")); assertFalse(File(repo, "should-not-run").exists())
        assertEquals("ok", call(tool, "edit_file", "path" to "repo/src/main.ts", "old_text" to "1", "new_text" to "2").getString("status"))
    }
    @Test fun nestedRulesBlockFirstWriteAndInvalidateWhenChangedOrTurnRestarts() = fixture { tool, root ->
        File(root, "AGENTS.md").writeText("Parent rules")
        File(root, "src").mkdir(); File(root, "src/AGENTS.md").writeText("Nested rules")
        val result = call(tool, "write_file", "path" to "src/new.py", "content" to "x=1\n")
        assertEquals("project_rules_required", result.getString("status")); assertFalse(result.getBoolean("applied")); assertEquals(2, result.getJSONArray("project_rules").length())
        assertFalse(File(root, "src/new.py").exists())
        assertEquals("ok", call(tool, "write_file", "path" to "src/new.py", "content" to "x=1\n").getString("status"))
        File(root, "src/AGENTS.md").writeText("New nested rules")
        assertEquals("project_rules_required", call(tool, "edit_file", "path" to "src/new.py", "old_text" to "1", "new_text" to "2").getString("status"))
        assertEquals("x=1\n", File(root, "src/new.py").readText())
        tool.beginTurn("chat")
        assertEquals("project_rules_required", call(tool, "write_file", "path" to "else.py", "content" to "x").getString("status"))
        assertEquals(1, call(tool, "read_file", "path" to "AGENTS.md").getJSONArray("project_rules").length())
    }
    @Test fun patchPreflightsEveryFileAndDryRunIsReadOnly() = fixture { tool, root ->
        File(root, "a.txt").writeText("one\n")
        val invalid = patch("*** Update File: a.txt\n@@\n-one\n+two\n*** Update File: missing.txt\n@@\n-x\n+y")
        assertEquals("error", call(tool, "apply_patch", "patch" to invalid).getString("status")); assertEquals("one\n", File(root, "a.txt").readText())
        val valid = patch("*** Update File: a.txt\n@@\n-one\n+two\n*** Add File: src/b.txt\n+created")
        val preview = call(tool, "apply_patch", "patch" to valid, "dry_run" to true)
        assertEquals(2, preview.getJSONArray("changes").length()); assertEquals(0, preview.getJSONArray("applied_paths").length())
        assertEquals("one\n", File(root, "a.txt").readText()); assertFalse(File(root, "src").exists())
        val applied = call(tool, "apply_patch", "patch" to valid)
        assertEquals("ok", applied.getString("status")); assertEquals(2, applied.getJSONArray("applied_paths").length())
        assertEquals("two\n", File(root, "a.txt").readText()); assertEquals("created\n", File(root, "src/b.txt").readText())
    }
    @Test fun patchRejectsAmbiguityAliasesTraversalAndLinksWithoutChangingFiles() = fixture { tool, root ->
        val file = File(root, "a").apply { writeText("same\nsame\n") }
        val original = file.readText()
        val bodies = listOf("*** Update File: a\n@@\n-same\n+new", "*** Delete File: a\n*** Add File: ./a\n+new", "*** Add File: ../escape\n+bad", "*** Add File: /tmp/escape\n+bad")
        for (body in bodies) {
            assertEquals("error", call(tool, "apply_patch", "patch" to patch(body)).getString("status")); assertEquals(original, file.readText())
        }
        Files.createSymbolicLink(File(root, "link").toPath(), file.toPath())
        assertEquals("error", call(tool, "apply_patch", "patch" to patch("*** Delete File: link")).getString("status")); assertEquals(original, file.readText())
    }
    @Test fun patchKeepsModesBomMixedLineEndingsAndFinalNewlineState() = fixture { tool, root ->
        val file = File(root, "a").apply { writeText("\uFEFFfirst\r\nsecond\nthird") }
        val mode = PosixFilePermissions.fromString("rwx------"); Files.setPosixFilePermissions(file.toPath(), mode)
        val result = call(tool, "apply_patch", "patch" to patch("*** Update File: a\n@@\n first\n+inserted\n second\n-third\n+last"))
        assertEquals(result.toString(), "ok", result.getString("status")); assertEquals("\uFEFFfirst\r\ninserted\r\nsecond\nlast", file.readText())
        assertEquals(mode, Files.getPosixFilePermissions(file.toPath()))
        assertEquals("ok", call(tool, "apply_patch", "patch" to patch("*** Delete File: a")).getString("status")); assertFalse(file.exists())
    }
    @Test fun patchReportsPartialCommitAndDoesNotClaimRollback() = fixture { tool, root ->
        File(root, "a").writeText("old\n"); File(root, "b").writeText("old\n")
        val args = JSONObject().put("patch", patch("*** Update File: a\n@@\n-old\n+new\n*** Update File: b\n@@\n-old\n+new"))
        var changed = false
        val result = JSONObject(tool.execute("chat", "apply_patch", args) {
            if (!changed && File(root, "a").readText() == "new\n") { File(root, "b").writeText("external change\n"); changed = true }
        })
        assertEquals("partial_failure", result.getString("status")); assertEquals("a", result.getJSONArray("applied_paths").getString(0))
        assertEquals("new\n", File(root, "a").readText()); assertEquals("external change\n", File(root, "b").readText())
    }
    @Test fun projectSkipsSymlinkTreesAndReportsTruncatedMapsAndLargeRules() = fixture { tool, root ->
        File(root, "AGENTS.md").writeText("Rule. ".repeat(3000))
        repeat(130) { File(root, "source$it.py").writeText("x=1") }
        val outside = Files.createTempDirectory("outside-coding").toFile()
        try {
            File(outside, "secret.py").writeText("private")
            Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
            val raw = tool.execute("chat", "project", JSONObject()); val result = JSONObject(raw)
            assertTrue(raw.length < 36000); assertTrue(result.getBoolean("map_truncated")); assertFalse(result.toString().contains("secret.py"))
            assertTrue(result.getJSONArray("project_rules").getJSONObject(0).getBoolean("content_truncated"))
        } finally { outside.deleteRecursively() }
    }
    @Test fun patchCancellationBeforeCommitPreservesOriginalsAndInvalidSchemasFail() = fixture { tool, root ->
        File(root, "a").writeText("old\n")
        assertThrows(CancellationException::class.java) { tool.execute("chat", "apply_patch", JSONObject().put("patch", patch("*** Delete File: a"))) { throw CancellationException() } }
        assertEquals("old\n", File(root, "a").readText())
        for ((name, args) in listOf("project" to JSONObject().put("path", false), "apply_patch" to JSONObject().put("patch", "x").put("dry_run", "true"), "project" to JSONObject().put("execute", true))) {
            assertEquals("error", JSONObject(tool.execute("chat", name, args)).getString("status"))
        }
        assertFalse(CodingContract.specifications().toString().any { it in '\u4e00'..'\u9fff' })
    }
    @Test fun structuralConflictsAndOversizedContextSearchFailBeforeWriting() = fixture { tool, root ->
        File(root, "a").writeText("keep\n")
        val structural = patch("*** Delete File: a\n*** Add File: a/child\n+new")
        assertEquals("error", call(tool, "apply_patch", "patch" to structural).getString("status"))
        assertEquals("keep\n", File(root, "a").readText())
        assertThrows(IllegalArgumentException::class.java) {
            TextPatch.apply("x\n".repeat(100001), listOf(TextPatch.Hunk(listOf("x"), listOf("y"))))
        }
    }

}
