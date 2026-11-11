package ai.capri.host

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Read-only index of operator-curated Skill manifests. No remote fetching or activation. */
internal class HostSkillCatalog(private val root: Path) {
    fun execute(args: JSONObject): JSONObject {
        val name = args.optString("name")
        val version = args.optString("version")
        val query = args.optString("query").trim()
        if (name.isNotEmpty() && !name.matches(IDENTIFIER) || version.isNotEmpty() && !version.matches(VERSION))
            throw WorkspaceFailure("invalid_catalog_reference", "Use a valid skill name and version.")
        if (args.getString("action") == "inspect") {
            if (name.isEmpty() || version.isEmpty())
                throw WorkspaceFailure("invalid_catalog_reference", "Provide both a skill name and a version.")
            return release(name, version).let { bundle ->
                bundle.put("resources", JSONArray(bundle.getJSONObject("files").keys().asSequence().sorted().toList()))
                bundle.remove("files")
                bundle
            }
        }
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return JSONObject().put("configured", false).put("skills", JSONArray())
        val names = list(root, IDENTIFIER, 101)
        val matches = names.filter { (name.isEmpty() || it == name) && it.contains(query, ignoreCase = true) }
        return JSONObject().put("configured", true).put("skills", JSONArray(matches.take(100).map { id ->
            JSONObject().put("name", id).put("versions", JSONArray(list(root.resolve(id), VERSION, 20)))
        })).put("truncated", names.size > 100)
    }

    fun release(name: String, version: String): JSONObject {
        if (!name.matches(IDENTIFIER) || !version.matches(VERSION))
            throw WorkspaceFailure("invalid_catalog_reference", "Use a valid skill name and version.")
        val skill = root.resolve(name)
        val release = skill.resolve(version)
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || !Files.isDirectory(skill, NOFOLLOW_LINKS) ||
            !Files.isDirectory(release, NOFOLLOW_LINKS))
            throw WorkspaceFailure("skill_not_found", "The skill version is unavailable in this catalog.")
        val manifest = release.resolve("SKILL.md")
        if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS))
            throw WorkspaceFailure("skill_not_found", "This version has no Skill manifest.")
        val bytes = Files.newInputStream(manifest, NOFOLLOW_LINKS).use { it.readNBytes(16_385) }
        if (bytes.size > 16_384) throw WorkspaceFailure("skill_manifest_too_large", "The Skill manifest exceeds 16 KiB.")
        val content = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        val files = JSONObject()
        val resources = release.resolve("resources")
        if (Files.isSymbolicLink(resources))
            throw WorkspaceFailure("invalid_skill_resource", "Skill resource directories cannot be symbolic links.")
        if (Files.isDirectory(resources, NOFOLLOW_LINKS)) Files.walk(resources, 8).use { paths ->
            val entries = paths.filter { it != resources }.limit(97).toList().sorted()
            if (entries.size > 96) throw WorkspaceFailure("skill_bundle_too_large", "The skill has too many resource entries.")
            var total = bytes.size
            for (entry in entries) {
                if (Files.isSymbolicLink(entry))
                    throw WorkspaceFailure("invalid_skill_resource", "Skill resources cannot be symbolic links.")
                if (Files.isDirectory(entry, NOFOLLOW_LINKS)) continue
                val relative = resources.relativize(entry).toString()
                if (!Files.isRegularFile(entry, NOFOLLOW_LINKS) || relative.length > 512 ||
                    relative.any { it.isISOControl() || it == '\\' } || Path.of(relative).any { it.toString().startsWith('.') })
                    throw WorkspaceFailure("invalid_skill_resource", "Use regular, visible text resources.")
                if (files.length() >= 32) throw WorkspaceFailure("skill_bundle_too_large", "A skill may have up to 32 resources.")
                val data = Files.newInputStream(entry, NOFOLLOW_LINKS).use { it.readNBytes(65_537) }
                total += data.size
                if (data.size > 65_536 || total > 262_144)
                    throw WorkspaceFailure("skill_bundle_too_large", "Skill resources exceed the bundle size limit.")
                val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString()
                if ('\u0000' in decoded) throw WorkspaceFailure("invalid_skill_resource", "Resources must be UTF-8 text.")
                val nameBytes = relative.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(8).putInt(nameBytes.size).putInt(data.size).array())
                digest.update(nameBytes)
                digest.update(data)
                files.put(relative, decoded)
            }
        }
        return JSONObject().put("name", name).put("version", version).put("revision", HexFormat.of().formatHex(digest.digest()))
            .put("source", "operator_catalog").put("content", content).put("files", files)
    }

    private fun list(directory: Path, pattern: Regex, limit: Long): List<String> {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) && it.fileName.toString().matches(pattern) }
                .map { it.fileName.toString() }.sorted().limit(limit).toList()
        }
    }

    companion object {
        private val IDENTIFIER = Regex("[a-z0-9][a-z0-9-]{0,63}")
        private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}
