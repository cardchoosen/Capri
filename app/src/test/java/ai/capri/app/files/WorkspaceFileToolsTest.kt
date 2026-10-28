package ai.capri.app.files

import ai.capri.app.Workspace
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

class WorkspaceFileToolsTest {
    private fun fixture(block: (WorkspaceFileTools, File) -> Unit) {
        val base = Files.createTempDirectory("capri-files-").toFile()
        try { block(WorkspaceFileTools(Workspace(base)), File(base, "chat/workspace").apply { mkdirs() }) }
        finally { base.deleteRecursively() }
    }
    private fun call(tool: WorkspaceFileTools, name: String, vararg args: Pair<String, Any>) =
        JSONObject(tool.execute("chat", name, JSONObject(mapOf(*args))))
    private fun error(result: JSONObject, code: String) { assertEquals("error", result.getString("status")); assertEquals(code, result.getJSONObject("error").getString("code")) }

    @Test fun legacyWritesGlobSearchAndPagination() = fixture { tool, root ->
        assertTrue(call(tool, "write_file", "path" to "a.kt", "content" to "needle\nNeedle\n").getBoolean("changed"))
        File(root, "src").mkdir(); File(root, "src/b.kt").writeText("needle")
        File(root, ".git").mkdir(); File(root, ".git/hidden.kt").writeText("needle")
        File(root, "binary.kt").writeBytes(byteArrayOf(0, 1))
        val page = call(tool, "list_files", "pattern" to "**/*.kt", "limit" to 1)
        assertEquals("a.kt", page.getJSONArray("files").getJSONObject(0).getString("path")); assertTrue(page.getBoolean("has_more"))
        assertEquals(2, call(tool, "list_files", "pattern" to "**/*.kt", "offset" to page.getInt("next_offset")).getJSONArray("files").length())
        val result = call(tool, "search_files", "query" to "needle", "case_sensitive" to false, "limit" to 1)
        assertTrue(result.getBoolean("has_more")); assertEquals(1, result.getJSONArray("matches").getJSONObject(0).getInt("line"))
        val next = call(tool, "search_files", "query" to "needle", "case_sensitive" to false, "offset" to result.getInt("next_offset"))
        assertEquals(2, next.getJSONArray("matches").length()); assertFalse(next.getBoolean("has_more")); assertEquals(1, next.getInt("skipped_files"))
        assertEquals(1, call(tool, "list_files", "pattern" to ".git/*", "include_ignored" to true).getJSONArray("files").length())
    }
    @Test fun longUnicodeAndEscapedReadsContinueWithoutLoss() = fixture { tool, root ->
        val original = "\uFEFF" + "😀\t\"\\\u2028".repeat(12000) + "\r\nlast\r\n"
        File(root, "long.txt").writeText(original)
        val recovered = StringBuilder(); var line = 1; var column = 0; var hash: String? = null
        do {
            val args = JSONObject().put("path", "/workspace/long.txt").put("start_line", line).put("start_column", column).put("max_lines", 1)
            hash?.let { args.put("expected_sha256", it) }
            val raw = tool.execute("chat", "read_file", args); assertTrue(raw.length <= 36000)
            val page = JSONObject(raw); assertEquals("ok", page.getString("status")); recovered.append(page.getString("content")); hash = page.getString("sha256")
            if (!page.getBoolean("has_more")) break
            line = page.getInt("next_line"); column = page.getInt("next_column")
        } while (recovered.length <= original.length)
        assertEquals(original, recovered.toString())
        File(root, "long.txt").appendText("changed")
        error(call(tool, "read_file", "path" to "long.txt", "expected_sha256" to hash!!), "conflict")
    }
    @Test fun exactEditsPreserveBytesPermissionsAndRejectAmbiguity() = fixture { tool, root ->
        val file = File(root, "script.sh"); file.writeText("\uFEFFone\r\none\r\n")
        val permissions = PosixFilePermissions.fromString("rwx------"); Files.setPosixFilePermissions(file.toPath(), permissions)
        val before = file.readBytes()
        error(call(tool, "edit_file", "path" to "script.sh", "old_text" to "one", "new_text" to "two"), "ambiguous_match")
        error(call(tool, "edit_file", "path" to "script.sh", "old_text" to "missing", "new_text" to "two"), "text_not_found")
        assertArrayEquals(before, file.readBytes())
        val hash = call(tool, "read_file", "path" to "script.sh").getString("sha256")
        val result = call(tool, "edit_file", "path" to "script.sh", "old_text" to "one", "new_text" to "two", "replace_all" to true, "expected_sha256" to hash)
        assertEquals(2, result.getInt("replacements")); assertEquals("\uFEFFtwo\r\ntwo\r\n", file.readText())
        assertEquals(permissions, Files.getPosixFilePermissions(file.toPath()))
        error(call(tool, "write_file", "path" to "script.sh", "content" to "lost", "expected_sha256" to hash), "conflict")
        assertFalse(call(tool, "write_file", "path" to "script.sh", "content" to file.readText()).getBoolean("changed"))
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".capri-edit-") })
    }
    @Test fun schemaAndRuntimeRejectInvalidInputAndEncoding() = fixture { tool, root ->
        error(call(tool, "read_file", "path" to "a", "max_lines" to 0), "invalid_arguments")
        error(call(tool, "read_file", "path" to "a", "max_lines" to "2"), "invalid_arguments")
        error(call(tool, "list_files", "bogus" to true), "invalid_arguments")
        error(call(tool, "write_file", "path" to "x"), "invalid_arguments")
        error(call(tool, "edit_file", "path" to "x", "old_text" to "", "new_text" to "x"), "invalid_arguments")
        error(call(tool, "search_files", "query" to "a\nb"), "invalid_arguments")
        File(root, "bad").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        error(call(tool, "read_file", "path" to "bad"), "unsupported_encoding")
        error(call(tool, "write_file", "path" to "x", "content" to "\uD800"), "unsupported_encoding")
        error(call(tool, "write_file", "path" to "x", "content" to "中".repeat(340000)), "invalid_arguments")
        assertFalse(File(root, "x").exists())
        assertEquals(5, FileToolContract.specifications().size)
        FileToolContract.specifications().forEach {
            val function = it.getJSONObject("function"); assertFalse(function.toString().any { c -> c in '\u4e00'..'\u9fff' })
            assertFalse(function.getJSONObject("parameters").getBoolean("additionalProperties"))
        }
    }
    @Test fun pathsCannotEscapeOrFollowLinks() = fixture { tool, root ->
        val outside = File(root.parentFile, "secret").apply { writeText("private") }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        for (path in listOf("../secret", outside.absolutePath, "/workspace/../secret")) error(call(tool, "read_file", "path" to path), "invalid_arguments")
        error(call(tool, "read_file", "path" to "link"), "unsafe_path")
        error(call(tool, "write_file", "path" to "link", "content" to "changed"), "unsafe_path")
        assertEquals("private", outside.readText()); assertEquals(0, call(tool, "list_files").getJSONArray("files").length())
        val other = File(root.parentFile.parentFile, "other").apply { mkdir() }
        Files.createSymbolicLink(File(root, "directory").toPath(), other.toPath())
        error(call(tool, "write_file", "path" to "directory/new", "content" to "changed"), "unsafe_path")
        assertFalse(File(other, "new").exists())
    }
    @Test fun cancellationDuringAtomicWriteLeavesOriginalAndNoTempFiles() = fixture { tool, root ->
        val file = File(root, "original").apply { writeText("keep") }
        var cancelled = false
        try {
            tool.execute("chat", "write_file", JSONObject().put("path", "original").put("content", "replace")) {
                if (root.listFiles()!!.any { it.name.startsWith(".capri-edit-") }) throw CancellationException()
            }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled); assertEquals("keep", file.readText()); assertEquals(listOf("original"), root.listFiles()!!.map { it.name })
    }
    @Test fun concurrentVersionedEditsHaveExactlyOneWinner() = fixture { tool, root ->
        File(root, "counter").writeText("initial")
        val hash = call(tool, "read_file", "path" to "counter").getString("sha256")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { value -> executor.submit<String> { call(tool, "write_file", "path" to "counter", "content" to "$value", "expected_sha256" to hash).getString("status") } }.map { it.get() }
            assertEquals(1, results.count { it == "ok" }); assertEquals(1, results.count { it == "error" })
        } finally { executor.shutdownNow() }
    }
}
