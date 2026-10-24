package ai.capri.app.extensions

import ai.capri.app.RuntimeFiles
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

internal object ToolPackPolicy {
    fun mcpId(id: String) = "pack-${ExtensionPolicy.id(id)}".also { require(it.length <= 64) { "工具包ID最长59字符" } }
    fun manifest(directory: File): JSONObject {
        val file = File(directory, "toolpack.json")
        require(file.isFile && file.length() <= 128_000) { "工具包根部需有不超过128KB的toolpack.json" }
        val obj = try { JSONObject(file.readText()) } catch (_: Exception) { error("toolpack.json格式错误") }
        require(obj.keys().asSequence().all { it in setOf("schema_version", "id", "name", "version", "source", "description", "mcp") }) { "工具包清单包含未知字段" }
        require(obj.opt("schema_version") is Number && obj.get("schema_version").toString() == "1") { "仅支持schema_version=1" }
        mcpId(obj.getString("id"))
        require(obj.getString("name").length in 1..120)
        require(obj.getString("version").matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}"))) { "工具包version无效" }
        val uri = java.net.URI(obj.getString("source"))
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "source须为不含凭据的HTTPS来源地址" }
        require(!obj.has("description") || (obj.opt("description") is String && obj.getString("description").length <= 2000))
        val mcp = obj.getJSONObject("mcp")
        require(mcp.keys().asSequence().all { it in setOf("type", "url", "command", "python_packages", "npm_packages", "timeout_seconds", "tools", "credential", "token_env", "read_only") }) { "mcp仅包含服务参数；ID、名称、启停由工具包管理，密钥在运行页绑定" }
        ExtensionPolicy.config(config(obj, true))
        return obj
    }
    fun config(manifest: JSONObject, enabled: Boolean): JSONObject = JSONObject(manifest.getJSONObject("mcp").toString())
        .put("id", mcpId(manifest.getString("id"))).put("name", manifest.getString("name"))
        .put("description", manifest.optString("description")).put("enabled", enabled)
    fun hash(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.forEach {
            digest.update((it.relativeTo(directory).invariantSeparatorsPath + "\u0000" + RuntimeFiles.sha(it) + "\n").toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    suspend fun <T> withSource(source: File, scratchRoot: File, block: suspend (File) -> T): T {
        if (source.isDirectory) return block(source)
        require(source.isFile && source.length() <= ExtensionInstall.MAX_BYTES) { "工具包须为目录或不超过10MB的ZIP" }
        val stage = File(scratchRoot, "toolpack-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            source.inputStream().use { raw -> ZipInputStream(raw).use { input ->
                var count = 0; var bytes = 0L
                while (true) {
                    val entry = input.nextEntry ?: break
                    require(++count <= ExtensionInstall.MAX_FILES) { "工具包超过500项" }
                    val file = ExtensionPolicy.relative(stage, entry.name.trimEnd('/'))
                    if (entry.isDirectory) file.mkdirs() else {
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= ExtensionInstall.MAX_BYTES) { "工具包解压超过10MB" }; output.write(buffer, 0, n) }
                        }
                    }
                }
            } }
            val manifests = stage.walkTopDown().filter { it.isFile && it.name == "toolpack.json" }.toList()
            require(manifests.size == 1) { "ZIP须只含一个toolpack.json" }
            return block(manifests.single().parentFile!!)
        } finally { RuntimeFiles.delete(stage) }
    }
}
