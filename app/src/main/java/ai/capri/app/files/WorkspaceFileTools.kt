package ai.capri.app.files

import ai.capri.app.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Direct, bounded UTF-8 workspace operations; never follows symbolic links. */
internal class WorkspaceFileTools(private val workspace: Workspace) {
    private class Failure(val code: String, message: String, val details: JSONObject = JSONObject()) : Exception(message)
    private data class TextFile(val bytes: ByteArray, val text: String, val sha: String)
    private data class Scan(val files: List<File>, val truncated: Boolean)
    companion object {
        val names = setOf("list_files", "read_file", "write_file", "search_files", "edit_file")
        private const val MAX_FILE = 8 * 1024 * 1024
        private const val MAX_SCAN = 10000
        private const val RESULT_BUDGET = 24000
        private val locks = ConcurrentHashMap<String, Any>()
        private val ignored = setOf(".git", ".venv", "venv", "node_modules", ".cache", "__pycache__")
        fun failure(code: String, message: String): String = JSONObject().put("status", "error")
            .put("error", JSONObject().put("code", code).put("message", message)).toString()
    }
    fun execute(chat: String, name: String, args: JSONObject, checkCancelled: () -> Unit = {}): String {
        try {
            require(chat.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid workspace identifier." }
            val root = File(workspace.sessionDirectory(chat), "workspace").absoluteFile
            for (directory in listOf(root.parentFile!!.parentFile!!, root.parentFile!!, root)) rejectLink(directory)
            Files.createDirectories(root.toPath())
            val result = synchronized(locks.getOrPut(root.path) { Any() }) {
                checkCancelled()
                FileToolContract.validate(name, args)
                when (name) {
                    "list_files" -> list(root, args, checkCancelled)
                    "read_file" -> read(root, args, checkCancelled)
                    "write_file" -> write(root, args, checkCancelled)
                    "search_files" -> search(root, args, checkCancelled)
                    "edit_file" -> edit(root, args, checkCancelled)
                    else -> throw Failure("unknown_tool", "Unknown workspace tool.")
                }
            }
            val encoded = result.toString()
            check(encoded.length <= 36000) { "Workspace result exceeded its serialization budget." }
            return encoded
        } catch (cancel: java.util.concurrent.CancellationException) { throw cancel }
        catch (e: Failure) { return e.details.put("status", "error").put("error", JSONObject().put("code", e.code).put("message", e.message)).toString() }
        catch (e: IllegalArgumentException) { return failure("invalid_arguments", e.message ?: "Invalid workspace arguments.") }
        catch (_: java.io.IOException) { return failure("io_error", "Unable to access the workspace file. No successful write is confirmed.") }
        catch (_: UnsupportedOperationException) { return failure("unsupported_filesystem", "The filesystem does not support these atomic workspace operations.") }
        catch (_: SecurityException) { return failure("access_denied", "Workspace file access was denied.") }
    }
    private fun ok() = JSONObject().put("status", "ok")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun rejectLink(file: File) {
        if (Files.isSymbolicLink(file.toPath())) throw Failure("unsafe_path", "Symbolic links are not supported by workspace file tools.")
    }
    private fun path(root: File, raw: String, allowRoot: Boolean = false): File {
        val clean = if (raw == "/workspace") "" else raw.removePrefix("/workspace/")
        require(clean.length <= 1024 && '\\' !in clean && clean.none(Char::isISOControl) && !File(clean).isAbsolute) { "Use a workspace-relative path without control characters." }
        val parts = clean.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." } && (allowRoot || parts.isNotEmpty())) { "Path must stay inside the current workspace." }
        var target = root
        for (part in parts) { target = File(target, part); rejectLink(target) }
        return target
    }
    private fun relative(root: File, file: File) = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
    private fun load(file: File, check: () -> Unit): TextFile {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw Failure("not_found", "A regular workspace file is required.")
        if (file.length() > MAX_FILE) throw Failure("file_too_large", "Text file exceeds the 8 MiB file limit. Use a bounded terminal command for larger files.")
        val bytes = ByteArrayOutputStream().use { output ->
            Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    check(); val size = input.read(buffer); if (size < 0) break
                    if (output.size() + size > MAX_FILE) throw Failure("file_too_large", "Text file exceeds the 8 MiB file limit.")
                    output.write(buffer, 0, size)
                }
            }; output.toByteArray()
        }
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { throw Failure("unsupported_encoding", "Only valid UTF-8 text is supported. Use a format-specific reader for binary files.") }
        if ('\u0000' in text) throw Failure("unsupported_encoding", "Binary content is not supported by text file tools.")
        return TextFile(bytes, text, sha(bytes))
    }
    private fun expect(args: JSONObject, current: String?) {
        val expected = args.optString("expected_sha256").takeIf(String::isNotBlank) ?: return
        if (!expected.equals(current, ignoreCase = true)) throw Failure("conflict", "File version changed. Read the current file before retrying.", JSONObject().put("current_sha256", current ?: JSONObject.NULL))
    }
    private fun glob(pattern: String): (String) -> Boolean {
        if (pattern.isBlank()) return { true }
        val matcher = try { FileSystems.getDefault().getPathMatcher("glob:$pattern") }
            catch (_: Exception) { throw Failure("invalid_pattern", "Invalid file glob pattern.") }
        val atRoot = if (pattern.startsWith("**/")) FileSystems.getDefault().getPathMatcher("glob:" + pattern.removePrefix("**/")) else null
        return { matcher.matches(Paths.get(it)) || atRoot?.matches(Paths.get(it)) == true }
    }
    private fun scan(root: File, start: File, includeIgnored: Boolean, check: () -> Unit): Scan {
        if (!Files.exists(start.toPath(), LinkOption.NOFOLLOW_LINKS)) throw Failure("not_found", "Workspace path does not exist.")
        if (Files.isRegularFile(start.toPath(), LinkOption.NOFOLLOW_LINKS)) return Scan(listOf(start), false)
        if (!Files.isDirectory(start.toPath(), LinkOption.NOFOLLOW_LINKS)) throw Failure("invalid_path", "A file or directory is required.")
        val pending = java.util.ArrayDeque<File>(); pending.add(start)
        val files = mutableListOf<File>(); var visited = 0; var truncated = false
        while (pending.isNotEmpty() && visited < MAX_SCAN) {
            check(); val directory = pending.removeFirst(); val children = mutableListOf<File>()
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                for (entry in stream) {
                    check(); if (++visited > MAX_SCAN) { truncated = true; break }
                    val file = entry.toFile()
                    if (Files.isSymbolicLink(entry)) continue
                    val rel = relative(root, file)
                    if (!includeIgnored && rel.split('/').any { it.startsWith('.') || it in ignored }) continue
                    children += file
                }
            }
            for (file in children.sortedBy(File::getName)) {
                if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) pending.add(file)
                else if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) files += file
            }
        }
        return Scan(files.sortedBy { relative(root, it) }, truncated || pending.isNotEmpty())
    }
    private fun list(root: File, args: JSONObject, check: () -> Unit): JSONObject {
        val all = scan(root, path(root, args.optString("path"), true), args.optBoolean("include_ignored"), check)
        val matchPath = glob(args.optString("pattern"))
        val matches = all.files.filter { matchPath(relative(root, it)) }
        val offset = args.optInt("offset", 0); val limit = args.optInt("limit", 100); val entries = JSONArray()
        var next = offset.coerceAtMost(matches.size)
        while (next < matches.size && entries.length() < limit) {
            check(); val file = matches[next]
            val item = JSONObject().put("path", relative(root, file)).put("size_bytes", file.length())
            if (entries.toString().length + item.toString().length > RESULT_BUDGET) {
                if (entries.length() == 0) throw Failure("result_too_large", "Narrow the path; this result exceeds the output budget.")
                break
            }
            entries.put(item); next++
        }
        return ok().put("files", entries).put("next_offset", next).put("has_more", next < matches.size)
            .put("scan_truncated", all.truncated).put("matched_files", matches.size)
    }
    private fun read(root: File, args: JSONObject, check: () -> Unit): JSONObject {
        val file = path(root, args.getString("path")); val data = load(file, check); expect(args, data.sha)
        val text = data.text
        val totalLines = if (text.isEmpty()) 0 else 1 + text.count { it == '\n' } - if (text.endsWith('\n')) 1 else 0
        var line = args.optInt("start_line", 1) - 1; var column = args.optInt("start_column", 0)
        val firstLine = line + 1; val firstColumn = column; val maxLines = args.optInt("max_lines", 200)
        val content = StringBuilder(); var budget = 20000; var consumedLines = 0
        var lineStart = 0
        repeat(minOf(line, totalLines)) { check(); lineStart = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it + 1 } }
        while (line < totalLines && consumedLines < maxLines && budget > 6) {
            check(); val end = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it + 1 }
            val count = text.codePointCount(lineStart, end)
            require(column <= count) { "start_column exceeds the selected line length." }
            var position = text.offsetByCodePoints(lineStart, column)
            while (position < end) {
                val cp = text.codePointAt(position)
                val cost = when { cp in listOf(8, 9, 10, 12, 13, 34, 47, 92) -> 2; cp < 32 || cp in 128..159 || cp in 8192..8447 -> 6; else -> Character.charCount(cp) }
                if (cost > budget) break
                content.appendCodePoint(cp); budget -= cost; position += Character.charCount(cp); column++
            }
            if (position < end) break
            line++; column = 0; consumedLines++; lineStart = end
        }
        val more = line < totalLines
        return ok().put("path", relative(root, file)).put("content", content.toString()).put("sha256", data.sha)
            .put("start_line", firstLine).put("start_column", firstColumn).put("total_lines", totalLines).put("size_bytes", data.bytes.size)
            .put("has_more", more).put("next_line", if (more) line + 1 else JSONObject.NULL).put("next_column", if (more) column else 0)
    }
    private fun search(root: File, args: JSONObject, check: () -> Unit): JSONObject {
        val query = args.getString("query"); val matchPath = glob(args.optString("pattern"))
        val all = scan(root, path(root, args.optString("path"), true), args.optBoolean("include_ignored"), check)
        val offset = args.optInt("offset", 0); val limit = args.optInt("limit", 50); val matches = JSONArray()
        var seen = 0; var skipped = 0; var scanned = 0; var bytes = 0L; var more = false; var truncated = all.truncated
        outer@ for (file in all.files) {
            check(); if (!matchPath(relative(root, file))) continue
            if (file.length() > MAX_FILE) { skipped++; continue }
            if (++scanned > 2000 || bytes + file.length() > 16 * 1024 * 1024) { truncated = true; break }
            val data = try { load(file, check) } catch (_: Failure) { skipped++; continue } catch (_: java.io.IOException) { skipped++; continue }
            bytes += data.bytes.size
            for ((i, line) in data.text.lineSequence().withIndex()) {
                check(); val at = line.indexOf(query, ignoreCase = !args.optBoolean("case_sensitive", true)); if (at < 0) continue
                if (seen++ < offset) continue
                var start = (at - 100).coerceAtLeast(0)
                if (start > 0 && line[start].isLowSurrogate() && line[start - 1].isHighSurrogate()) start--
                var end = minOf(line.length, maxOf(start + 500, at + query.length))
                if (end < line.length && end > 0 && line[end - 1].isHighSurrogate() && line[end].isLowSurrogate()) end++
                val excerpt = line.substring(start, end)
                val item = JSONObject().put("path", relative(root, file)).put("line", i + 1).put("column", line.codePointCount(0, at) + 1).put("text", excerpt)
                    .put("line_truncated", start > 0 || start + excerpt.length < line.length)
                if (matches.length() >= limit || matches.toString().length + item.toString().length > RESULT_BUDGET) {
                    if (matches.length() == 0) throw Failure("result_too_large", "Narrow the path; this result exceeds the output budget.")
                    more = true; break@outer
                }
                matches.put(item)
            }
        }
        return ok().put("matches", matches).put("next_offset", offset + matches.length()).put("has_more", more)
            .put("scan_truncated", truncated).put("skipped_files", skipped).put("scanned_files", minOf(scanned, 2000))
    }
    private fun replace(root: File, file: File, bytes: ByteArray, previous: TextFile?, check: () -> Unit) {
        require(bytes.size <= MAX_FILE) { "Edited content exceeds the 8 MiB file limit." }
        Files.createDirectories(file.parentFile!!.toPath())
        path(root, relative(root, file))
        val staged = Files.createTempFile(file.parentFile!!.toPath(), ".capri-edit-", ".tmp")
        try {
            FileChannel.open(staged, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) { check(); channel.write(buffer) }; channel.force(true)
            }
            if (previous != null) {
                val current = load(file, check)
                if (current.sha != previous.sha) throw Failure("conflict", "File changed during the edit. Read it again before retrying.", JSONObject().put("current_sha256", current.sha))
                Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            } else if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw Failure("conflict", "The target was created by another writer. Read it before retrying.")
            check(); path(root, relative(root, file))
            Files.move(staged, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(staged) }
    }
    private fun encode(text: String): ByteArray {
        require('\u0000' !in text) { "NUL bytes are not supported in text files." }
        val encoded = try { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text)) }
            catch (_: java.nio.charset.CharacterCodingException) { throw Failure("unsupported_encoding", "Text contains invalid Unicode sequences.") }
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }
    private fun write(root: File, args: JSONObject, check: () -> Unit): JSONObject {
        val file = path(root, args.getString("path"))
        val previous = if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) load(file, check) else null
        expect(args, previous?.sha)
        val bytes = encode(args.getString("content"))
        require(bytes.size <= 1_000_000) { "content exceeds 1000000 UTF-8 bytes." }
        val changed = previous?.bytes?.contentEquals(bytes) != true
        if (changed) replace(root, file, bytes, previous, check)
        return ok().put("path", relative(root, file)).put("sha256", sha(bytes)).put("size_bytes", bytes.size).put("changed", changed)
    }
    private fun edit(root: File, args: JSONObject, check: () -> Unit): JSONObject {
        val file = path(root, args.getString("path")); val data = load(file, check); expect(args, data.sha)
        val old = args.getString("old_text"); val replacement = args.getString("new_text")
        var count = 0; var from = 0
        while (true) { check(); val at = data.text.indexOf(old, from); if (at < 0) break; count++; from = at + old.length }
        if (count == 0) throw Failure("text_not_found", "old_text does not match the current file. Read the relevant lines first.")
        if (count > 1 && !args.optBoolean("replace_all")) throw Failure("ambiguous_match", "old_text matches more than once. Include more context or explicitly set replace_all.", JSONObject().put("match_count", count))
        val updated = if (args.optBoolean("replace_all")) data.text.replace(old, replacement) else data.text.replaceFirst(old, replacement)
        val bytes = encode(updated); val changed = updated != data.text
        if (changed) replace(root, file, bytes, data, check)
        return ok().put("path", relative(root, file)).put("sha256", sha(bytes)).put("previous_sha256", data.sha)
            .put("size_bytes", bytes.size).put("replacements", if (args.optBoolean("replace_all")) count else 1).put("changed", changed)
    }
}
