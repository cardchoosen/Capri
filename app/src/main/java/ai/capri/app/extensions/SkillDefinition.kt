package ai.capri.app.extensions

import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files

internal data class SkillInfo(
    val id: String, val name: String, val description: String, val directory: File, val builtin: Boolean,
    val category: SkillCategory = SkillCategory.GENERAL, val body: String = "", val autoinvoke: Boolean = true,
    val file: File = File(directory, "SKILL.md"), val source: String = if (builtin) "builtin" else "user"
) {
    fun identity() = "$source:$id"
    fun revision(): String = SkillParser.hash(buildString {
        append(file.readText())
        SkillParser.resources(this@SkillInfo).forEach { append('\n').append(it.relativeTo(directory.canonicalFile).invariantSeparatorsPath).append(':').append(SkillParser.hash(it.readBytes())) }
    })
}

/** One parser for built-ins, imports, project files, catalogs and registered providers. */
internal object SkillParser {
    fun hash(text: String) = hash(text.toByteArray())
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun parse(directory: File, builtin: Boolean = false): SkillInfo = parseFile(File(directory, "SKILL.md"), builtin)
    fun parseFile(file: File, builtin: Boolean = false, source: String = if (builtin) "builtin" else "user"): SkillInfo {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() <= 128_000) { "Skill entry must be a regular UTF-8 file up to 128 KB." }
        val text = file.readText().replace("\r\n", "\n")
        val id = ExtensionPolicy.id(if (file.name == "SKILL.md") file.parentFile!!.name else file.nameWithoutExtension)
        var body = text
        val metadata: Map<*, *> = if (text.startsWith("---\n")) {
            val end = text.indexOf("\n---\n", 4); require(end >= 0) { "Unclosed Skill frontmatter." }
            val options = LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 0; codePointLimit = 128_000 }
            body = text.substring(end + 5)
            Yaml(SafeConstructor(options)).load<Any>(text.substring(4, end)) as? Map<*, *> ?: error("Skill frontmatter must be an object.")
        } else emptyMap<String, Any>()
        fun field(key: String, default: String): String { val value = metadata[key] ?: return default; require(value is String) { "$key must be a string." }; return value }
        val name = field("name", id); require(name.isNotBlank() && name.length <= 120) { "Skill name must contain 1–120 characters." }
        val description = field("description", ""); require(description.length <= 1024) { "Skill description exceeds 1024 characters." }
        val extra = metadata["metadata"] as? Map<*, *>
        fun flag(value: Any?): Boolean? = when(value?.toString()?.trim()?.lowercase()) { "true", "yes", "on", "1" -> true; "false", "no", "off", "0" -> false; else -> null }
        val automatic = flag(extra?.get("capri/autoinvoke")) ?: flag(extra?.get("opencode/autoinvoke")) ?: !(flag(metadata["disable-model-invocation"]) ?: false)
        return SkillInfo(id, name, description, file.parentFile!!, builtin, SkillCategory.from(extra?.get("capri/category") as? String), body.trim(), automatic, file, source)
    }
    fun resources(skill: SkillInfo): List<File> {
        if (skill.file.name != "SKILL.md") return emptyList()
        val root = skill.directory.canonicalFile; var bytes = 0L; var count = 0
        return root.walkTopDown().onEnter { dir ->
            require(!Files.isSymbolicLink(dir.toPath())) { "Skill resources cannot contain symlinks." }
            dir == root || dir.name !in setOf(".git", "node_modules", ".venv", "__pycache__")
        }.filter { it != root }.onEach {
            require(++count <= 500 && !Files.isSymbolicLink(it.toPath())) { "Skill resources exceed limits or contain symlinks." }
            if (it.isFile) { bytes += it.length(); require(bytes <= 10_000_000) { "Skill resources exceed 10 MB." } }
        }.filter { it.isFile && it.canonicalFile != skill.file.canonicalFile && !it.name.startsWith(".capri-") }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }
    fun summary(skill: SkillInfo, revision: String) = JSONObject().put("id", skill.id).put("name", skill.name)
        .put("description", skill.description).put("source", skill.source).put("revision", revision)
        .put("category", skill.category.id).put("autoinvoke", skill.autoinvoke)
}

/** Plugins register definitions, never execute during discovery. Later providers have explicit precedence. */
internal fun interface SkillProvider { fun definitions(chat: String): List<SkillInfo> }
