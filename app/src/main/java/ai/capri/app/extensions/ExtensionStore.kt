package ai.capri.app.extensions

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object ExtensionPolicy {
    fun id(value: String): String = value.also { require(it.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")) && it.length <= 64) { "ID 仅允许小写英文、数字及单个连字符，最长64字符" } }
    fun relative(root: File, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute && '\\' !in path) { "资源路径必须是相对路径" }
        val result = File(root, path).canonicalFile
        require(result.path.startsWith(root.canonicalPath + File.separator)) { "资源路径越界" }
        return result
    }
    fun config(raw: JSONObject): JSONObject {
        val obj = JSONObject(raw.toString())
        require(!obj.has("toolpack")) { "请使用普通MCP配置，不再支持工具包元数据" }
        id(obj.getString("id"))
        require(obj.optString("name").length in 1..120) { "请填写名称" }
        val type = obj.getString("type")
        require(type in listOf("remote", "local")) { "type 必须为 remote 或 local" }
        if (type == "remote") {
            val uri = java.net.URI(obj.getString("url"))
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.rawQuery == null) { "远端地址须为HTTPS，凭据请单独绑定，不放URL" }
        } else {
            val command = obj.getJSONArray("command")
            require(command.length() in 1..64 && (0 until command.length()).all { command.get(it) is String && command.getString(it).isNotBlank() && '\u0000' !in command.getString(it) }) { "command 须为非空字符串数组" }
        }
        val credential = obj.optString("credential", "stored")
        require(credential in listOf("stored", "github-account")) { "未知凭据来源" }
        if (credential == "github-account") {
            require(type == "remote")
            val uri = java.net.URI(obj.getString("url"))
            require(uri.host == "api.githubcopilot.com" && uri.port in listOf(-1, 443) && uri.path.startsWith("/mcp")) { "全局GitHub凭据只能绑定GitHub官方MCP地址" }
        }
        if (obj.has("token_env")) require(obj.getString("token_env").matches(Regex("[A-Z][A-Z0-9_]{0,63}")) && obj.getString("token_env") !in listOf("PATH", "HOME", "TMPDIR", "LD_PRELOAD", "PROOT_LOADER")) { "Token环境变量名称无效" }
        if (obj.has("tools")) {
            val tools = obj.getJSONArray("tools"); require(tools.length() <= 200 && (0 until tools.length()).all { tools.get(it) is String && tools.getString(it).length in 1..200 }) { "tools须为原工具名数组" }
        }
        require(obj.optInt("timeout_seconds", 60) in 5..300) { "调用超时须为5–300秒" }
        for (key in listOf("python_packages", "npm_packages")) {
            val packages = obj.optJSONArray(key) ?: continue
            require(packages.length() <= 50)
            for (i in 0 until packages.length()) require(packages.getString(i).matches(Regex("[A-Za-z0-9@_./=<>!~^+:-]{1,200}")) && !packages.getString(i).startsWith("-")) { "依赖声明无效" }
        }
        // Secrets have a separate encrypted settings field. Never accept arbitrary environment/header payloads.
        require(!obj.has("headers") && !obj.has("env") && !obj.has("token")) { "凭据请使用单独的Token字段；暂不支持任意headers/env" }
        return obj
    }
}

internal data class SkillInfo(val id: String, val name: String, val description: String, val directory: File, val builtin: Boolean, val category: SkillCategory = SkillCategory.GENERAL)
internal object SkillParser {
    fun parse(directory: File, builtin: Boolean = false): SkillInfo {
        val source = File(directory, "SKILL.md")
        require(source.isFile && source.length() <= 128_000) { "缺少SKILL.md或文件超过128KB" }
        val text = source.readText().replace("\r\n", "\n")
        require(text.startsWith("---\n")) { "SKILL.md需要YAML元数据" }
        val end = text.indexOf("\n---\n", 4)
        require(end >= 0) { "YAML元数据未闭合" }
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 0; codePointLimit = 128_000 }
        val metadata = Yaml(SafeConstructor(options)).load<Any>(text.substring(4, end)) as? Map<*, *> ?: error("元数据须为对象")
        val name = ExtensionPolicy.id(metadata["name"] as? String ?: error("缺少name"))
        require(name == directory.name) { "技能name必须与目录名一致" }
        val description = metadata["description"] as? String ?: error("缺少description")
        require(description.length in 1..1024)
        val category = (metadata["metadata"] as? Map<*, *>)?.get("capri/category") as? String
        return SkillInfo(name, name, description, directory, builtin, SkillCategory.from(category))
    }
}

/** Global definitions; per-chat activation is persisted separately. No secret material in config/list results. */
internal class ExtensionStore(private val context: Context) {
    val root = File(context.filesDir, "extensions").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("extensions", Context.MODE_PRIVATE)
    private val secretPrefs = context.getSharedPreferences("extension-secrets", Context.MODE_PRIVATE)
    private val configFile = AtomicFile(File(root, "mcp.json"))
    private val skillsRoot = File(root, "skills").apply { mkdirs() }
    companion object { private val lock = Any() }
    init {
        synchronized(lock) {
            if (!configFile.baseFile.exists()) writeConfigs(JSONArray().put(JSONObject("""{"id":"github","name":"GitHub","type":"remote","url":"https://api.githubcopilot.com/mcp/","enabled":false,"read_only":true,"description":"仓库、Issue与PR；绑定GitHub Token后按需连接","timeout_seconds":60}""")).put(JSONObject("""{"id":"context7","name":"Context7","type":"remote","url":"https://mcp.context7.com/mcp","enabled":false,"description":"软件库文档查询；支持匿名限流访问，可绑定Key","timeout_seconds":60}""")))
            recoverLegacyMcp()
            // Preserve definitions, IDs, credentials, dependencies and source paths; only retire the wrapper metadata.
            val definitions = configs()
            if (definitions.any { it.has("toolpack") || it.optString("description").contains("工具包凭据页") }) {
                val converted = definitions.map { original ->
                    val copy = JSONObject(original.toString())
                    if (copy.has("toolpack")) {
                        // An unfinished old transaction must stay recoverable; keep it disabled until recovery succeeds.
                        runCatching { checkLegacyMcpReady(copy.getString("id")); copy.remove("toolpack") }
                            .onFailure { copy.put("enabled", false) }
                    }
                    if (copy.optString("description").contains("工具包凭据页")) copy.put("description", copy.getString("description").replace("工具包凭据页", "MCP 配置页"))
                    copy
                }
                writeConfigs(JSONArray(converted))
            }
            for (id in context.assets.list("extensions/skills").orEmpty()) {
                val dir = File(skillsRoot, id).apply { mkdirs() }
                // Builtins update with the APK. User imports may not overwrite them.
                copyBuiltin("extensions/skills/$id", dir)
            }
        }
    }
    private fun copyBuiltin(asset: String, target: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isNotEmpty()) {
            target.mkdirs()
            children.forEach { copyBuiltin("$asset/$it", File(target, it)) }
        } else {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            if (!target.isFile || !target.readBytes().contentEquals(bytes)) {
                val af = AtomicFile(target); val output = af.startWrite()
                try { output.write(bytes); af.finishWrite(output) } catch (error: Exception) { af.failWrite(output); throw error }
            }
        }
    }
    private fun atomic(file: File, text: String) {
        val af = AtomicFile(file); val output = af.startWrite()
        try { output.write(text.toByteArray()); af.finishWrite(output) } catch (e: Exception) { af.failWrite(output); throw e }
    }
    private fun writeConfigs(data: JSONArray) = atomic(configFile.baseFile, data.toString(2))
    fun configs(): List<JSONObject> = synchronized(lock) {
        val data = JSONArray(configFile.openRead().bufferedReader().use { it.readText() })
        (0 until data.length()).map { data.getJSONObject(it) }
    }
    fun config(id: String) = configs().firstOrNull { it.getString("id") == id } ?: error("MCP配置不存在")
    fun saveConfig(raw: JSONObject) = synchronized(lock) {
        checkLegacyMcpReady(ExtensionPolicy.id(raw.getString("id")))
        val obj = ExtensionPolicy.config(raw)
        val all = configs().filterNot { it.getString("id") == obj.getString("id") } + obj
        require(all.size <= 100) { "最多100个MCP配置" }
        writeConfigs(JSONArray(all))
    }
    /** Add-only publication: model installation cannot overwrite an existing definition or credential. */
    fun installConfig(raw: JSONObject, source: File? = null) = synchronized(lock) {
        val config = ExtensionPolicy.config(raw)
        val id = config.getString("id")
        require(configs().none { it.getString("id") == id }) { "同名MCP已存在，请先list核验；安装不会覆盖已有配置" }
        require(source == null || config.getString("type") == "local")
        val target = File(root, "mcp/$id")
        require(!target.exists()) { "该ID已有依赖目录，请使用其他ID或在运行页处理旧配置" }
        val stage = File(root, "install-${UUID.randomUUID()}").apply { mkdirs() }
        var published = false
        try {
            val current = File(stage, "current")
            if (source != null) ExtensionInstall.copyDirectory(source, current) else check(current.mkdirs())
            target.parentFile!!.mkdirs()
            check(stage.renameTo(target)) { "MCP源码安装未完成" }; published = true
            saveConfig(config)
        } catch (error: Exception) {
            if (published) ai.capri.app.RuntimeFiles.delete(target)
            throw error
        } finally { ai.capri.app.RuntimeFiles.delete(stage) }
    }
    private fun clearActive(id: String) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("active:") && it.endsWith(":$id") }.forEach { edit.remove(it) }
        edit.apply()
    }
    private fun clearLegacyRuntime(mcpId: String) {
        val sessions = File(context.filesDir, "sessions")
        require(!java.nio.file.Files.isSymbolicLink(sessions.toPath()))
        sessions.listFiles().orEmpty().filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { chat ->
            val extensions = File(chat, "extensions")
            require(!java.nio.file.Files.isSymbolicLink(extensions.toPath())) { "会话扩展目录异常，卸载尚未完成" }
            ai.capri.app.RuntimeFiles.delete(File(extensions, mcpId))
        }
    }
    /** Read-only compatibility with transactions left by older APKs; no new package installation API. */
    private fun recoverLegacyMcp() {
        File(root, "mcp").listFiles().orEmpty().filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.name.startsWith("pack-") }.forEach { directory ->
            // Leave an unrecoverable transaction visible on disk; never activate its partial contents.
            runCatching { recoverLegacyDirectory(directory) }
        }
    }
    private fun recoverLegacyDirectory(directory: File) {
        val removing = android.util.AtomicFile(File(directory, ".toolpack-remove.json"))
        if (!removing.baseFile.exists() && !File(removing.baseFile.path + ".bak").exists() && File(removing.baseFile.path + ".new").exists()) removing.delete()
        if (removing.baseFile.exists() || File(removing.baseFile.path + ".bak").exists()) {
            val id = JSONObject(removing.openRead().bufferedReader().use { it.readText() }).getString("id")
            require(ExtensionPolicy.id("pack-$id") == directory.name)
            val existing = configs().firstOrNull { it.getString("id") == directory.name }
            require(existing == null || existing.optJSONObject("toolpack")?.optString("id") == id)
            removeConfig(directory.name); clearActive(directory.name); clearLegacyRuntime(directory.name); ai.capri.app.RuntimeFiles.delete(directory); return
        }
        val file = android.util.AtomicFile(File(directory, ".toolpack-transaction.json"))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            // No committed journal means switching current was never allowed to start.
            require(!File(directory, "current.previous").exists()) { "旧版MCP迁移记录缺失" }
            ai.capri.app.RuntimeFiles.delete(File(directory, "current.next")); file.delete()
            if (configs().none { it.getString("id") == directory.name } && directory.listFiles().orEmpty().isEmpty()) ai.capri.app.RuntimeFiles.delete(directory)
            return
        }
        val journal = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val config = journal.getJSONObject("config")
        require(config.getString("id") == directory.name && ExtensionPolicy.id("pack-${config.getJSONObject("toolpack").getString("id")}") == directory.name)
        val current = File(directory, "current"); val previous = File(directory, "current.previous")
        val published = configs().firstOrNull { it.getString("id") == directory.name }?.optJSONObject("toolpack")?.optString("sha256") == config.getJSONObject("toolpack").getString("sha256")
        if (published) {
            require(current.isDirectory) { "旧版MCP当前目录缺失" }
            ai.capri.app.RuntimeFiles.delete(previous); clearActive(directory.name)
        } else if (previous.exists()) {
            ai.capri.app.RuntimeFiles.delete(current); check(previous.renameTo(current))
        } else if (!journal.getBoolean("had_previous")) ai.capri.app.RuntimeFiles.delete(current)
        ai.capri.app.RuntimeFiles.delete(File(directory, "current.next")); file.delete()
        if (configs().none { it.getString("id") == directory.name } && directory.listFiles().orEmpty().isEmpty()) ai.capri.app.RuntimeFiles.delete(directory)
    }
    fun checkLegacyMcpReady(id: String) {
        if (id.startsWith("pack-")) {
            val directory = File(root, "mcp/$id")
            require(!File(directory, "current.previous").exists() && listOf(".toolpack-transaction.json", ".toolpack-remove.json").none { File(directory, it).exists() || File(directory, "$it.bak").exists() || File(directory, "$it.new").exists() }) { "旧版MCP事务未完成，请重启恢复后重试" }
        }
    }
    fun removeConfig(id: String) = synchronized(lock) { writeConfigs(JSONArray(configs().filterNot { it.getString("id") == id })); secretPrefs.edit().remove(id).apply(); Unit }
    fun skills(): List<SkillInfo> = synchronized(lock) { skillsRoot.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull {
        runCatching { SkillParser.parse(it, context.assets.list("extensions/skills").orEmpty().contains(it.name)) }.getOrNull()
    }.sortedBy { it.id } }
    fun category(skill: SkillInfo) = SkillCategory.from(prefs.getString("skill-category:${skill.id}", skill.category.id))
    fun setCategory(skill: SkillInfo, category: SkillCategory) = synchronized(lock) {
        require(skills().any { it.id == skill.id }) { "Skill no longer exists." }
        check(prefs.edit().putString("skill-category:${skill.id}", category.id).commit()) { "Unable to save Skill category." }
    }
    fun enabled(skill: SkillInfo) = prefs.getBoolean("skill:${skill.id}", true)
    fun enable(skill: SkillInfo, value: Boolean) { prefs.edit().putBoolean("skill:${skill.id}", value).apply() }
    fun skill(id: String) = skills().firstOrNull { it.id == id && enabled(it) } ?: error("技能不存在或已停用")
    fun <T> withSkill(id: String, block: (SkillInfo) -> T): T = synchronized(lock) { block(skill(id)) }
    fun removeSkill(id: String) = synchronized(lock) { val s = skills().first { it.id == id }; require(!s.builtin) { "内置技能可停用，不可删除" }; ai.capri.app.RuntimeFiles.delete(s.directory); prefs.edit().remove("skill-category:$id").apply() }
    fun importSkill(input: InputStream, zip: Boolean, replace: Boolean = false): String = synchronized(lock) {
        val stage = File(root, "import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            if (zip) {
                ZipInputStream(input).use { stream ->
                    var count = 0; var bytes = 0L
                    while (true) {
                        val entry = stream.nextEntry ?: break
                        require(++count <= 500) { "技能包文件过多" }
                        val file = ExtensionPolicy.relative(stage, entry.name.trimEnd('/'))
                        if (entry.isDirectory) file.mkdirs() else {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                while (true) { val n = stream.read(buffer); if (n < 0) break; bytes += n; require(bytes <= 10_000_000) { "技能包解压超过10MB" }; out.write(buffer, 0, n) }
                            }
                        }
                    }
                }
                val sources = stage.walkTopDown().filter { it.name == "SKILL.md" && it.isFile }.toList()
                require(sources.size == 1) { "请导入仅包含一个技能的ZIP" }
                val source = sources.single().parentFile!!
                // A root-level SKILL.md is accepted; determine its canonical name through frontmatter.
                val skillFile = File(source, "SKILL.md"); require(skillFile.length() <= 128_000) { "技能文件超过128KB" }
                val text = skillFile.readText()
                val name = nameOf(text)
                installStage(source, name, replace)
            } else {
                val raw = input.use { stream -> val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096); while (out.size() <= 128_000) { val n = stream.read(buffer, 0, minOf(buffer.size, 128_001 - out.size())); if (n < 0) break; out.write(buffer, 0, n) }; out.toByteArray() }; require(raw.size <= 128_000) { "技能文件超过128KB" }
                val text = raw.toString(Charsets.UTF_8); val name = nameOf(text)
                val source = File(stage, name).apply { mkdirs() }; File(source, "SKILL.md").writeText(text)
                installStage(source, name, replace)
            }
        } finally { ai.capri.app.RuntimeFiles.delete(stage) }
    }
    fun importSkillDirectory(source: File, replace: Boolean = false): String = synchronized(lock) {
        val stage = File(root, "import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val copy = File(stage, "source")
            ExtensionInstall.copyDirectory(source, copy)
            val skillFile = File(copy, "SKILL.md")
            require(skillFile.isFile && skillFile.length() <= 128_000) { "技能目录根部需有不超过128KB的SKILL.md" }
            installStage(copy, nameOf(skillFile.readText()), replace)
        } finally { ai.capri.app.RuntimeFiles.delete(stage) }
    }
    private fun nameOf(text: String): String {
        val normalized = text.replace("\r\n", "\n"); require(normalized.startsWith("---\n")); val end = normalized.indexOf("\n---\n", 4); require(end > 0)
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 0; codePointLimit = 128_000 }
        val header = Yaml(SafeConstructor(options)).load<Any>(normalized.substring(4, end)) as? Map<*, *> ?: error("元数据无效")
        return ExtensionPolicy.id(header["name"] as? String ?: error("缺少name"))
    }
    private fun installStage(source: File, name: String, replace: Boolean): String {
        val target = File(skillsRoot, name)
        require(name !in context.assets.list("extensions/skills").orEmpty()) { "内置技能不可覆盖" }
        require(!target.exists() || replace) { "同名技能已存在；明确更新用户技能时使用replace=true" }
        val validation = File(root, "validate-${UUID.randomUUID()}").apply { mkdirs() }
        val backup = File(validation, ".previous")
        try {
            val normalized = File(validation, name)
            check(source.renameTo(normalized)) { "无法准备技能目录" }
            SkillParser.parse(normalized)
            if (target.exists()) check(target.renameTo(backup)) { "无法暂存原技能" }
            if (!normalized.renameTo(target)) {
                if (backup.exists()) check(backup.renameTo(target)) { "技能恢复失败" }
                error("技能安装未完成，原技能已保留")
            }
            return name
        } finally {
            // Do not discard the original if publication failed and it could not be restored.
            if (backup.exists() && !target.exists()) backup.renameTo(target)
            if (!backup.exists() || target.exists()) ai.capri.app.RuntimeFiles.delete(validation)
        }
    }
    fun setActive(chat: String, id: String, active: Boolean) { prefs.edit().putBoolean("active:$chat:$id", active).apply() }
    fun clearChat(chat: String) {
        val edit = prefs.edit(); prefs.all.keys.filter { it.startsWith("active:$chat:") }.forEach { edit.remove(it) }; edit.apply()
    }
    fun active(chat: String, id: String) = prefs.getBoolean("active:$chat:$id", false)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("capri-extensions", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("capri-extensions", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generateKey()
        }
    }
    fun hasSecret(id: String) = secretPrefs.contains(id)
    fun saveSecret(id: String, token: String) {
        require(token.length <= 8192 && token.none { it.isISOControl() }) { "Token无效" }
        if (token.isBlank()) { secretPrefs.edit().remove(id).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        secretPrefs.edit().putString(id, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)).apply()
    }
    fun secret(id: String): String? {
        val raw = secretPrefs.getString(id, null) ?: return null
        val pieces = raw.split(':')
        return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP))); String(doFinal(Base64.decode(pieces[1], Base64.NO_WRAP))) }
    }
}
