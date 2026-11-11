package ai.capri.host

import ai.capri.core.ToolContracts
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal class WorkspaceFailure(val code: String, message: String) : IOException(message)

internal class WorkspaceTools(private val root: Path, private val publish: (String, Long) -> JSONObject) {
    private val managedSkills = ManagedSkillStore(root)
    fun importFile(name: String, content: ByteArray): JSONObject {
        if (name.length !in 1..180 || name in setOf(".", "..") ||
            name.startsWith(".capri-") || name.any { it == '/' || it == '\\' || it.isISOControl() }) {
            fail("invalid_name", "Use a file name without directory separators or control characters.")
        }
        if (content.size > maxBytes) fail("file_too_large", "The file exceeds the 1 MiB limit.")
        if (name.endsWith(".docx", ignoreCase = true)) {
            if (content.size < 4 || !content.copyOfRange(0, 4).contentEquals(byteArrayOf(80, 75, 3, 4)))
                fail("invalid_document", "Upload a valid DOCX file.")
        } else if (name.endsWith(".pdf", ignoreCase = true)) {
            if (!content.take(5).toByteArray().contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)))
                fail("invalid_document", "Upload a valid PDF file.")
        } else if (name.substringAfterLast('.').lowercase() in imageExtensions) {
            if (imageMime(content) == null) fail("invalid_image", "Upload a PNG, JPEG, GIF, or WebP image.")
        } else if (name.endsWith(".wav", ignoreCase = true)) wavInfo(content)
        else text(content)
        return save("inputs/${UUID.randomUUID()}/$name", content).put("status", "ok")
    }

    fun execute(name: String, args: JSONObject): JSONObject {
        val relative = if (name == "message") args.getString("media") else args.optString("path", ".")
        return when (name) {
            "read" -> read(relative, args.optInt("offset", 1), args.optInt("limit", 200))
            "write" -> save(relative, args.getString("content").toByteArray(Charsets.UTF_8))
            "edit" -> edit(relative, args.getString("oldText"), args.getString("newText"))
            "ls" -> list(relative, args.optInt("limit", 100))
            "grep" -> search(relative, args.getString("pattern"), args.optInt("limit", 50))
            "project" -> project(relative)
            "coding_reference" -> read(relative, args.getInt("line"), args.optInt("limit", 20))
            "coding_diagnostics" -> diagnostics(relative)
            "coding_restore" -> restore(relative, args)
            "apply_patch" -> patch(args)
            "skill" -> skill(args)
            "audio_info" -> wavInfo(bytes(relative)).put("path", relative)
            "document_extract" -> {
                val path = resolve(relative)
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) fail("file_not_found", "The path is not a regular file.")
                if (Files.size(path) > maxBytes) fail("file_too_large", "The file exceeds the 1 MiB limit.")
                when (path.fileName.toString().substringAfterLast('.').lowercase()) {
                    "docx" -> extractDocx(path)
                    "pdf" -> extractPdf(bytes(relative), args.optInt("firstPage", 1), args.optInt("pageLimit", 5))
                    else -> fail("unsupported_format", "This tool currently extracts text from DOCX and PDF files.")
                }.put("path", relative)
            }
            "message" -> {
                val path = root.relativize(attachmentPath(relative)).toString()
                val content = attachmentBytes(path)
                JSONObject().put("attachment", publish(path, content.size.toLong()))
            }
            else -> error("Workspace tool dispatch is missing.")
        }.put("status", "ok")
    }

    fun uploadedImage(relative: String): Pair<String, ByteArray> {
        val path = root.relativize(resolve(relative))
        if (path.nameCount != 3 || path.getName(0).toString() != "inputs" ||
            path.fileName.toString().substringAfterLast('.').lowercase() !in imageExtensions)
            fail("invalid_image", "Use an uploaded workspace image.")
        val content = bytes(relative)
        return (imageMime(content) ?: fail("invalid_image", "The image format is unsupported.")) to content
    }

    fun uploadedAudio(relative: String): ByteArray {
        val path = root.relativize(resolve(relative))
        if (path.nameCount != 3 || path.getName(0).toString() != "inputs" ||
            !path.fileName.toString().endsWith(".wav", ignoreCase = true))
            fail("invalid_audio", "Use an uploaded PCM WAV file.")
        return bytes(relative).also { wavInfo(it) }
    }

    fun installCatalogSkill(name: String, expected: String?, manifest: JSONObject): JSONObject {
        if (!name.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) fail("invalid_skill_name", "Use a lowercase skill identifier.")
        val relative = "skills/$name/SKILL.md"
        val previous = managedSkills.active(name)?.getString("revision") ?: legacySkillRevision(relative)
        if (previous != expected) fail("skill_revision_conflict", "The workspace Skill changed. Inspect its current revision before installing.")
        managedSkills.install(name, manifest)
        return JSONObject().put("path", relative).put("revision", manifest.getString("revision"))
            .put("previousRevision", previous ?: JSONObject.NULL).put("version", manifest.getString("version"))
    }

    fun rollbackCatalogSkill(name: String, expected: String, revision: String): JSONObject {
        if (!name.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) fail("invalid_skill_name", "Use a lowercase skill identifier.")
        val relative = "skills/$name/SKILL.md"
        val active = managedSkills.active(name)?.getString("revision")
        val legacy = legacySkillRevision(relative)
        if ((active ?: legacy) != expected) fail("skill_revision_conflict", "The installed Skill changed.")
        if (revision == legacy && active != null) managedSkills.activate(name, null)
        else if (!managedSkills.activate(name, revision)) {
            if (active == null) return restore(relative, JSONObject().put("revision", expected).put("restoreRevision", revision))
            fail("revision_not_found", "The requested Skill package revision is no longer retained.")
        }
        return JSONObject().put("path", relative).put("revision", revision)
    }

    fun catalogSkillState(name: String): JSONObject {
        if (!name.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) fail("invalid_skill_name", "Use a lowercase skill identifier.")
        val relative = "skills/$name/SKILL.md"
        val legacy = legacySkillRevision(relative)
        val active = managedSkills.active(name)?.getString("revision")
        val current = active ?: legacy
        val reference = snapshotPath(relative, "reference")
        val prefix = reference.fileName.toString().removeSuffix("reference")
        val retained = if (Files.isDirectory(reference.parent, NOFOLLOW_LINKS)) Files.list(reference.parent).use { entries ->
            entries.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.fileName.toString().startsWith(prefix) }
                .map { it.fileName.toString().removePrefix(prefix) }.filter { it != current && it.matches(Regex("[a-f0-9]{64}")) }
                .sorted().limit(100).toList()
        } else emptyList()
        val revisions = (managedSkills.retained(name) + listOfNotNull(legacy) + if (active == null) retained else emptyList())
            .distinct().filter { it != current }
        return JSONObject().put("installedRevision", current ?: JSONObject.NULL).put("retainedRevisions", JSONArray(revisions))
    }

    private fun legacySkillRevision(relative: String): String? =
        if (Files.exists(resolve(relative), NOFOLLOW_LINKS)) revision(bytes(relative)) else null

    private fun attachmentPath(relative: String): Path {
        val path = resolve(relative)
        val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
        if (extension !in setOf("md", "txt", "csv", "json", "yaml", "yml"))
            fail("unsupported_media", "Publish a Markdown, text, CSV, JSON, or YAML file.")
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) fail("file_not_found", "The path is not a regular file.")
        return path
    }

    fun attachmentBytes(relative: String): ByteArray {
        attachmentPath(relative)
        return bytes(relative).also { text(it) }
    }

    fun attachmentSize(relative: String): Long = Files.size(attachmentPath(relative))

    fun sourceRevision(relative: String): String = revision(bytes(relative))

    fun inventory(): JSONObject {
        val files = mutableListOf<JSONObject>()
        var visited = 0
        var truncated = false
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && !visibleName(dir)) return FileVisitResult.SKIP_SUBTREE
                return countEntry()
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (countEntry() == FileVisitResult.TERMINATE) return FileVisitResult.TERMINATE
                if (attrs.isRegularFile && visibleName(file)) files.add(JSONObject()
                    .put("path", root.relativize(file).toString()).put("size", attrs.size()))
                return FileVisitResult.CONTINUE
            }

            private fun countEntry(): FileVisitResult {
                truncated = ++visited > 5_000 || files.size >= 1_000
                return if (truncated) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        })
        return JSONObject().put("files", JSONArray(files.sortedBy { it.getString("path") })).put("truncated", truncated)
    }

    fun download(relative: String): Pair<String, ByteArray> =
        resolve(relative).fileName.toString() to bytes(relative)

    private fun visibleName(path: Path): Boolean =
        path.fileName.toString().let { name ->
            !name.startsWith(".capri-") && name.none { it == '\\' || it.isISOControl() }
        }

    private fun resolve(relative: String, createParents: Boolean = false): Path {
        val path = Path.of(relative)
        if (path.isAbsolute || relative.any { it == '\\' || it.isISOControl() } || path.any { it.toString() == ".." })
            fail("invalid_path", "Use a relative path inside the current workspace.")
        val parts = path.normalize().filter { it.toString().isNotEmpty() }
        if (parts.any { it.toString().startsWith(".capri-") })
            fail("invalid_path", "This name is reserved.")
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) fail("workspace_unavailable", "The workspace is unavailable.")
        var current = root
        for ((index, part) in parts.withIndex()) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) fail("invalid_path", "Symbolic links are unavailable.")
            if (createParents && index < parts.lastIndex && Files.notExists(current, NOFOLLOW_LINKS))
                Files.createDirectory(current)
        }
        return current
    }

    private fun bytes(relative: String): ByteArray {
        val path = resolve(relative)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) fail("file_not_found", "The path is not a regular file.")
        val result = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(maxBytes + 1) }
        if (result.size > maxBytes) fail("file_too_large", "The file exceeds the 1 MiB limit.")
        return result
    }

    private fun text(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        fail("invalid_text", "The file is not valid UTF-8.")
    }

    private fun read(relative: String, offset: Int, limit: Int): JSONObject {
        return readPage(relative, bytes(relative), offset, limit)
    }

    private fun readPage(relative: String, original: ByteArray, offset: Int, limit: Int): JSONObject {
        val lines = text(original).lines()
        val page = lines.drop(offset - 1).take(limit)
        val content = StringBuilder()
        var consumed = 0
        for (line in page) {
            if (content.length + line.length + 1 > 24_000) {
                if (consumed == 0) fail("line_too_long", "The requested line exceeds the response limit.")
                break
            }
            if (consumed > 0) content.append('\n')
            content.append(line)
            consumed++
        }
        val next = offset.toLong() + consumed
        return JSONObject().put("path", relative).put("content", content.toString())
            .put("revision", revision(original)).put("startLine", offset)
            .put("totalLines", lines.size).put("nextOffset", if (consumed > 0 && next <= lines.size) next else JSONObject.NULL)
    }

    private fun project(relative: String): JSONObject {
        val directory = resolve(relative)
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) fail("directory_not_found", "The path is not a directory.")
        val markers = mapOf("settings.gradle.kts" to "gradle", "settings.gradle" to "gradle",
            "build.gradle.kts" to "gradle", "build.gradle" to "gradle", "pom.xml" to "maven",
            "package.json" to "node", "pyproject.toml" to "python", "Cargo.toml" to "rust", "go.mod" to "go")
        val detected = markers.filterKeys { Files.isRegularFile(directory.resolve(it), NOFOLLOW_LINKS) }
        return JSONObject().put("path", relative).put("markers", JSONArray(detected.keys))
            .put("projectTypes", JSONArray(detected.values.distinct()))
    }

    private fun diagnostics(relative: String): JSONObject {
        if (!relative.endsWith(".json", ignoreCase = true))
            fail("unsupported_language", "This host currently supports diagnostics for .json files.")
        val original = bytes(relative)
        val source = text(original)
        val diagnostics = JSONArray()
        try {
            var depth = 0
            var quote: Char? = null
            var escaped = false
            for (character in source) {
                if (quote != null) {
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == quote) quote = null
                } else when (character) {
                    '"', '\'' -> quote = character
                    '{', '[' -> if (++depth > 64) throw JSONException("JSON nesting exceeds 64 levels.")
                    '}', ']' -> if (--depth < 0) throw JSONException("Unexpected closing delimiter.")
                }
            }
            val parser = JSONTokener(source)
            parser.nextValue()
            if (parser.nextClean() != '\u0000') throw JSONException("Unexpected trailing input.")
        } catch (error: JSONException) {
            diagnostics.put(JSONObject().put("severity", "error").put("code", "json_parse_error")
                .put("message", (error.message ?: "JSON parsing failed.").take(240)))
        }
        return JSONObject().put("path", relative).put("revision", revision(original))
            .put("engine", "org.json").put("diagnostics", diagnostics)
    }

    private data class PreparedPatch(val path: String, val original: ByteArray, val updated: ByteArray, val changes: Int)

    private fun preparePatch(args: JSONObject): PreparedPatch {
        val relative = root.relativize(resolve(args.getString("path"))).toString()
        val original = bytes(relative)
        if (revision(original) != args.getString("revision"))
            fail("revision_conflict", "The file changed. Read it again before applying a patch.")
        var updated = text(original)
        val changes = args.getJSONArray("changes")
        for (i in 0 until changes.length()) {
            val change = changes.getJSONObject(i)
            val old = change.getString("oldText")
            val start = updated.indexOf(old)
            if (start < 0 || updated.indexOf(old, start + 1) >= 0)
                fail("edit_conflict", "Each old text must appear exactly once. No changes were applied.")
            updated = updated.replaceRange(start, start + old.length, change.getString("newText"))
            if (updated.length > maxBytes) fail("file_too_large", "The patch exceeds the 1 MiB limit.")
        }
        val content = updated.toByteArray(Charsets.UTF_8)
        if (content.size > maxBytes) fail("file_too_large", "The patch exceeds the 1 MiB limit.")
        return PreparedPatch(relative, original, content, changes.length())
    }

    private fun patch(args: JSONObject): JSONObject {
        val files = args.optJSONArray("files")
        val patches = if (files == null) {
            if (!args.has("path") || !args.has("revision") || !args.has("changes"))
                fail("invalid_patch", "Provide path, revision and changes, or a files array.")
            listOf(preparePatch(args))
        } else {
            if (args.length() != 1) fail("invalid_patch", "Use only files when applying a multi-file patch.")
            (0 until files.length()).map { preparePatch(files.getJSONObject(it)) }
        }
        if (patches.map { it.path }.toSet().size != patches.size)
            fail("invalid_patch", "Provide each file path only once.")
        patches.forEach { snapshot(it.path, it.original) }
        val applied = mutableListOf<PreparedPatch>()
        val results = JSONArray()
        try {
            for (patch in patches) {
                if (!bytes(patch.path).contentEquals(patch.original))
                    fail("revision_conflict", "A file changed during patch application.")
                results.put(save(patch.path, patch.updated).put("revision", revision(patch.updated))
                    .put("changes", patch.changes))
                applied.add(patch)
            }
        } catch (failure: IOException) {
            var restored = true
            for (patch in applied.asReversed()) {
                try {
                    if (bytes(patch.path).contentEquals(patch.updated)) save(patch.path, patch.original)
                    else restored = false
                } catch (_: IOException) {
                    restored = false
                }
            }
            if (!restored) fail("patch_partial", "Some files could not be rolled back. Inspect current revisions before retrying.")
            throw failure
        }
        return if (files == null) results.getJSONObject(0) else JSONObject().put("files", results)
    }

    private fun restore(relative: String, args: JSONObject): JSONObject {
        val current = bytes(relative)
        if (revision(current) != args.getString("revision"))
            fail("revision_conflict", "The file changed. Read it again before restoring a revision.")
        val wanted = args.getString("restoreRevision")
        if (!wanted.matches(Regex("[a-f0-9]{64}"))) fail("invalid_revision", "Provide a SHA-256 revision.")
        val path = snapshotPath(relative, wanted)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS))
            fail("revision_not_found", "No retained snapshot has that revision for this file.")
        val restored = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(maxBytes + 1) }
        if (restored.size > maxBytes || revision(restored) != wanted)
            fail("revision_unavailable", "The retained snapshot failed validation.")
        snapshot(relative, current)
        return save(relative, restored).put("revision", wanted)
    }

    private fun snapshotPath(relative: String, revision: String): Path {
        val snapshots = root.resolve(".capri-revisions")
        if (Files.exists(snapshots, NOFOLLOW_LINKS) && !Files.isDirectory(snapshots, NOFOLLOW_LINKS))
            fail("revision_unavailable", "The revision store is unavailable.")
        val fileKey = revision(root.relativize(resolve(relative)).toString().toByteArray(Charsets.UTF_8))
        return snapshots.resolve("$fileKey-$revision")
    }

    private fun snapshot(relative: String, bytes: ByteArray) {
        val path = snapshotPath(relative, revision(bytes))
        if (Files.isRegularFile(path, NOFOLLOW_LINKS)) return
        Files.createDirectories(path.parent)
        val full = Files.list(path.parent).use { it.limit(100).count() >= 100 }
        if (full) fail("revision_limit", "The workspace has reached its retained revision limit.")
        val temporary = Files.createTempFile(path.parent, ".pending-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, path, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun skill(args: JSONObject): JSONObject {
        val name = args.optString("name")
        if (name.isNotEmpty()) {
            if (!name.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) fail("invalid_skill_name", "Use a lowercase skill identifier.")
            val base = "skills/$name"
            managedSkills.active(name)?.let { bundle ->
                val revision = bundle.getString("revision")
                if (args.has("revision") && args.getString("revision") != revision)
                    fail("skill_revision_conflict", "The installed Skill revision changed.")
                val files = bundle.getJSONObject("files")
                val resource = args.optString("resource")
                val selected = if (resource.isEmpty()) bundle.getString("content") else files.opt(resource) as? String
                    ?: fail("invalid_resource", "Use a resource listed in this Skill package.")
                val path = if (resource.isEmpty()) "$base/SKILL.md" else "$base/resources/$resource"
                val page = readPage(path, selected.toByteArray(Charsets.UTF_8), args.optInt("offset", 1), 100)
                return if (resource.isEmpty()) page.put("revision", revision).put("version", bundle.getString("version"))
                    .put("resources", JSONArray(files.keys().asSequence().sorted().toList()))
                else page.put("skillRevision", revision)
            }
            val manifest = read("$base/SKILL.md", args.optInt("offset", 1), 100)
            val version = manifest.getString("revision")
            if (args.has("revision") && args.getString("revision") != version)
                fail("skill_revision_conflict", "The skill changed. Load its current revision before using its resources.")
            val resource = args.optString("resource")
            if (resource.isNotEmpty()) {
                if (Path.of(resource).isAbsolute)
                    fail("invalid_resource", "Use a path relative to this skill's resources directory.")
                return read("$base/resources/$resource", args.optInt("offset", 1), 100).put("skillRevision", version)
            }
            val resources = resolve("$base/resources")
            val files = if (Files.isDirectory(resources, NOFOLLOW_LINKS)) Files.walk(resources, 4).use { paths ->
                paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) &&
                    resources.relativize(it).none { part -> part.toString().startsWith(".capri-") } }
                    .limit(33).map { resources.relativize(it).toString() }.toList().sorted()
            } else emptyList()
            return manifest.put("resources", JSONArray(files.take(32))).put("resourcesTruncated", files.size > 32)
        }
        if (args.has("resource") || args.has("revision"))
            fail("invalid_skill_name", "Provide a skill name before selecting its revision or resource.")
        val directory = resolve("skills")
        val local = if (Files.isDirectory(directory, NOFOLLOW_LINKS)) Files.list(directory).use { paths ->
            paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) &&
                Files.isRegularFile(it.resolve("SKILL.md"), NOFOLLOW_LINKS) &&
                it.fileName.toString().matches(Regex("[a-z0-9][a-z0-9-]{0,63}")) }
                .map { it.fileName.toString() }.limit(101).toList().sorted()
        } else emptyList()
        val names = (local + managedSkills.names()).distinct().sorted()
        return JSONObject().put("skills", JSONArray(names.take(100))).put("truncated", names.size > 100)
    }

    fun delegationSource(relative: String): JSONObject {
        val name = resolve(relative).fileName.toString()
        if (name.substringAfterLast('.', "").lowercase() !in setOf("md", "txt", "csv", "json", "yaml", "yml"))
            fail("unsupported_source", "Delegate analysis of UTF-8 documents only.")
        return read(relative, 1, 80)
    }

    private fun save(relative: String, content: ByteArray): JSONObject {
        if (content.size > maxBytes) fail("file_too_large", "The file exceeds the 1 MiB limit.")
        val path = resolve(relative, createParents = true)
        if (path == root || Files.exists(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS))
            fail("invalid_path", "Use a regular workspace file path.")
        val temporary = Files.createTempFile(path.parent, ".capri-write-", ".tmp")
        try {
            Files.write(temporary, content)
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return JSONObject().put("path", relative).put("size", content.size)
    }

    private fun edit(relative: String, old: String, new: String): JSONObject {
        val original = text(bytes(relative))
        val start = original.indexOf(old)
        if (start < 0 || original.indexOf(old, start + 1) >= 0)
            fail("edit_conflict", "The exact old text must appear once.")
        return save(relative, original.replaceRange(start, start + old.length, new).toByteArray(Charsets.UTF_8))
    }

    private fun list(relative: String, limit: Int): JSONObject {
        val path = resolve(relative)
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) fail("directory_not_found", "The path is not a directory.")
        val entries = Files.list(path).use { stream ->
            stream.filter { !Files.isSymbolicLink(it) && !it.fileName.toString().startsWith(".capri-") }
                .limit(limit.toLong() + 1).toList().sorted()
        }
        return JSONObject().put("entries", JSONArray(entries.take(limit).map {
            JSONObject().put("path", root.relativize(it).toString()).put("directory", Files.isDirectory(it, NOFOLLOW_LINKS))
        })).put("truncated", entries.size > limit)
    }

    private fun search(relative: String, pattern: String, limit: Int): JSONObject {
        val start = resolve(relative)
        if (Files.notExists(start, NOFOLLOW_LINKS)) fail("file_not_found", "The search path does not exist.")
        val matches = JSONArray()
        var visited = 0
        var scannedBytes = 0L
        var truncated = false
        Files.walk(start, 8).use { stream ->
            val paths = stream.iterator()
            while (paths.hasNext()) {
                if (++visited > 500 || matches.length() >= limit || scannedBytes >= 8L * maxBytes) {
                    truncated = true
                    break
                }
                val file = paths.next()
                if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || file.fileName.toString().startsWith(".capri-")) continue
                val path = root.relativize(file).toString()
                val data = try { bytes(path) } catch (_: WorkspaceFailure) { continue }
                scannedBytes += data.size
                val content = try { text(data) } catch (_: WorkspaceFailure) { continue }
                for ((index, line) in content.lineSequence().withIndex()) {
                    if (!line.contains(pattern)) continue
                    if (matches.length() == limit) {
                        truncated = true
                        break
                    }
                    matches.put(JSONObject().put("path", path).put("line", index + 1).put("text", line.take(500)))
                }
            }
        }
        return JSONObject().put("matches", matches).put("truncated", truncated)
    }

    companion object {
        private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")
        const val maxBytes = 1024 * 1024
        private fun revision(bytes: ByteArray): String =
            java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun fail(code: String, message: String): Nothing = throw WorkspaceFailure(code, message)

        private fun string(description: String, min: Int, max: Int) =
            JSONObject().put("type", "string").put("description", description).put("minLength", min).put("maxLength", max)

        private fun integer(description: String, max: Int) =
            JSONObject().put("type", "integer").put("description", description).put("minimum", 1).put("maximum", max)

        private val path get() = string("Relative path within the current session workspace.", 1, 512)

        fun specifications(): List<JSONObject> = listOf(
            ToolContracts.spec("read", "Read bounded lines from a UTF-8 workspace file.",
                JSONObject().put("path", path).put("offset", integer("One-based starting line. Default: 1.", Int.MAX_VALUE))
                    .put("limit", integer("Maximum lines. Default: 200.", 400)), listOf("path")),
            ToolContracts.spec("write", "Create or replace a UTF-8 workspace file atomically. Creates parent directories.",
                JSONObject().put("path", path).put("content", string("Complete file content, up to 1 MiB in UTF-8.", 0, maxBytes)),
                listOf("path", "content")),
            ToolContracts.spec("edit", "Replace one exact, unique occurrence of text in a UTF-8 workspace file.",
                JSONObject().put("path", path).put("oldText", string("Exact text to replace.", 1, maxBytes))
                    .put("newText", string("Replacement text. An empty string removes the match.", 0, maxBytes)),
                listOf("path", "oldText", "newText")),
            ToolContracts.spec("ls", "List one workspace directory. The default is the workspace root.",
                JSONObject().put("path", path).put("limit", integer("Maximum entries. Default: 100.", 200)), emptyList()),
            ToolContracts.spec("grep", "Find case-sensitive literal text in UTF-8 workspace files. Results are bounded.",
                JSONObject().put("pattern", string("Literal text to find.", 1, 512)).put("path", path)
                    .put("limit", integer("Maximum matches. Default: 50.", 100)), listOf("pattern")),
            ToolContracts.spec("message", "Attach a workspace text document to the current conversation. Downloads read the current workspace file.",
                JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray().put("send")))
                    .put("media", path), listOf("action", "media")),
            ToolContracts.spec("project", "Detect project types from manifest names in a workspace directory.",
                JSONObject().put("path", path), emptyList()),
            ToolContracts.spec("coding_reference", "Read a bounded source excerpt and return its file revision and line range.",
                JSONObject().put("path", path).put("line", integer("One-based starting line.", Int.MAX_VALUE))
                    .put("limit", integer("Maximum lines. Default: 20.", 100)), listOf("path", "line")),
            ToolContracts.spec("coding_diagnostics", "Run the host JSON parser on a workspace .json file and report parse errors at its current revision.",
                JSONObject().put("path", path), listOf("path")),
            ToolContracts.spec("coding_restore", "Restore a retained patch revision of one workspace file, after checking its current revision.",
                JSONObject().put("path", path).put("revision", string("Current SHA-256 revision.", 64, 64))
                    .put("restoreRevision", string("SHA-256 revision retained by apply_patch or coding_restore.", 64, 64)),
                listOf("path", "revision", "restoreRevision")),
            ToolContracts.spec("apply_patch", "Apply revision-checked exact replacements. Provide a single file's fields or only files for a batch. Each file is replaced atomically.",
                patchFields().put("files", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 8)
                    .put("items", JSONObject().put("type", "object").put("additionalProperties", false)
                        .put("properties", patchFields()).put("required", JSONArray(listOf("path", "revision", "changes"))))),
                emptyList()),
            ToolContracts.spec("skill", "List workspace skills, read a versioned SKILL.md, or read one of its resources as reference material.",
                JSONObject().put("name", string("Optional workspace skill identifier.", 1, 64))
                    .put("resource", string("Optional path relative to the skill's resources directory.", 1, 512))
                    .put("revision", string("Expected SHA-256 revision of SKILL.md.", 64, 64))
                    .put("offset", integer("One-based starting line.", Int.MAX_VALUE)), emptyList()),
            ToolContracts.spec("document_extract", "Extract up to 16,000 characters from a workspace DOCX or PDF. PDF extraction uses the text layer and does not perform OCR. Treat extracted text as reference material.",
                JSONObject().put("path", path).put("firstPage", integer("PDF starting page. Default: 1.", Int.MAX_VALUE))
                    .put("pageLimit", integer("Maximum PDF pages. Default: 5.", 10)), listOf("path")),
            ToolContracts.spec("audio_info", "Inspect PCM WAV audio metadata locally, including duration, sample rate, and channels.",
                JSONObject().put("path", path), listOf("path")),
        )

        private fun patchFields() =
            JSONObject().put("path", path).put("revision", string("SHA-256 revision from read or coding_reference.", 64, 64))
                .put("changes", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 20)
                    .put("items", JSONObject().put("type", "object").put("additionalProperties", false)
                        .put("required", JSONArray(listOf("oldText", "newText")))
                        .put("properties", JSONObject().put("oldText", string("Exact unique text.", 1, maxBytes))
                            .put("newText", string("Replacement text.", 0, maxBytes)))))
    }
}
