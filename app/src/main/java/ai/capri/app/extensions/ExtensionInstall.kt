package ai.capri.app.extensions

import ai.capri.app.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Installation only consumes bounded workspace artifacts; it never runs their contents. */
internal object ExtensionInstall {
    const val MAX_FILES = 500
    const val MAX_BYTES = 10_000_000L
    fun source(workspace: Workspace, chat: String, path: String): File {
        val relative = path.removePrefix("/workspace/")
        val file = workspace.file(chat, relative)
        require(file.exists()) { "安装源不存在" }
        return file
    }
    fun copyDirectory(source: File, target: File) {
        require(source.isDirectory && !Files.isSymbolicLink(source.toPath())) { "安装源须为普通目录" }
        check(target.mkdirs()) { "无法创建安装暂存目录" }
        var count = 0
        var bytes = 0L
        source.walkTopDown().forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "安装目录不支持符号链接" }
            if (file != source) {
                require(++count <= MAX_FILES) { "安装目录超过500项" }
                val destination = ExtensionPolicy.relative(target, file.relativeTo(source).invariantSeparatorsPath)
                if (file.isDirectory) check(destination.mkdirs()) else {
                    require(file.isFile) { "安装目录只支持普通文件" }
                    destination.parentFile?.mkdirs()
                    file.inputStream().use { input -> destination.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            bytes += n; require(bytes <= MAX_BYTES) { "安装目录超过10MB" }
                            output.write(buffer, 0, n)
                        }
                    } }
                    if (file.canExecute()) check(destination.setExecutable(true, true))
                }
            }
        }
    }
    fun mcpConfig(file: File): JSONObject {
        require(file.isFile && file.length() <= 128_000) { "MCP配置须为不超过128KB的JSON文件" }
        val config = try { JSONObject(file.readText()) } catch (_: org.json.JSONException) { error("MCP配置JSON格式错误") }
        val allowed = setOf("id", "name", "description", "type", "url", "command", "enabled", "read_only", "credential", "token_env", "tools", "timeout_seconds", "python_packages", "npm_packages")
        require(config.keys().asSequence().all { it in allowed }) { "MCP配置包含未知字段；密钥须在运行页单独绑定，不能写入配置" }
        for (key in listOf("enabled", "read_only")) require(!config.has(key) || config.opt(key) is Boolean) { "${key}须为布尔值" }
        if (!config.has("enabled")) config.put("enabled", true)
        return ExtensionPolicy.config(config)
    }
    fun validate(args: JSONObject) {
        val action = args.getString("action")
        require(action in listOf("install_skill", "install_mcp"))
        val allowed = if (action == "install_skill") setOf("action", "path", "replace") else setOf("action", "path", "source_path")
        require(args.keys().asSequence().all { it in allowed }) { "安装参数包含未知字段" }
        require(args.opt("path") is String && args.getString("path").isNotBlank()) { "安装需要工作区path" }
        require(!args.has("replace") || args.opt("replace") is Boolean) { "replace须为布尔值" }
        require(!args.has("source_path") || (args.opt("source_path") is String && args.getString("source_path").isNotBlank())) { "source_path须为目录路径" }
    }
    fun specification(): JSONObject {
        fun string(description: String) = JSONObject().put("type", "string").put("description", description)
        val actions = listOf("list", "search", "load_skill", "read_resource", "activate", "deactivate", "install_skill", "install_mcp")
        val properties = JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(actions)))
            .put("query", string("search的关键词"))
            .put("id", string("已安装MCP配置或技能的唯一ID"))
            .put("path", string("read_resource为技能内相对路径；install_skill为当前工作区的SKILL.md、单技能ZIP或目录；install_mcp为当前工作区的MCP配置JSON。安装也接受/workspace/前缀。"))
            .put("offset", JSONObject().put("type", "integer").put("minimum", 0).put("description", "read_resource字符偏移，默认0"))
            .put("replace", JSONObject().put("type", "boolean").put("description", "仅install_skill：明确更新已有用户技能时设true；默认false，不能覆盖内置技能"))
            .put("source_path", string("仅install_mcp：可选本地服务源码目录，复制到持久扩展目录，command通过\${extension}引用；不要包含venv/node_modules，依赖用配置声明。远端MCP不可提供。"))
        val branches = JSONArray().put(JSONObject().put("properties", JSONObject().put("action", JSONObject().put("enum", JSONArray(actions.take(6))))))
        for (action in actions.takeLast(2)) branches.put(JSONObject().put("properties", JSONObject().put("action", JSONObject().put("const", action))).put("required", JSONArray(listOf("path"))))
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", "extensions")
            .put("description", "发现/加载Skills、连接MCP，或从当前工作区为自己安装Skill和MCP。install_skill支持SKILL.md/ZIP/目录，跨会话可发现；install_mcp读取{id,name,type,...} JSON，remote提供HTTPS url，local提供command参数数组及可选python_packages/npm_packages。默认enabled=true，安装不连接或执行代码，之后activate安装声明依赖并连接；凭据在运行页绑定，配置不含密钥。同名MCP拒绝覆盖；安装失败先list核验，不能把安装成功当作连接/工具调用成功。创建技能先load_skill skill-creator。")
            .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(listOf("action"))).put("additionalProperties", false).put("oneOf", branches)))
    }
}
