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
            .put("query", string("Search keywords."))
            .put("id", string("Installed MCP configuration or Skill ID."))
            .put("path", string("read_resource: path relative to the Skill. install_skill: workspace SKILL.md, single-Skill ZIP or directory. install_mcp: workspace MCP configuration JSON. Installation also accepts /workspace/ paths."))
            .put("offset", JSONObject().put("type", "integer").put("minimum", 0).put("description", "Character offset for read_resource, default 0."))
            .put("replace", JSONObject().put("type", "boolean").put("description", "install_skill only: true explicitly updates an existing user Skill. Default false; built-ins cannot be replaced."))
            .put("source_path", string("install_mcp only: optional local-server source directory copied to persistent extension storage, referenced with \${extension} in command. Exclude venv/node_modules and declare dependencies in configuration. Not accepted for remote MCP."))
        val branches = JSONArray().put(JSONObject().put("properties", JSONObject().put("action", JSONObject().put("enum", JSONArray(actions.take(6))))))
        for (action in listOf("install_skill", "install_mcp")) branches.put(JSONObject().put("properties", JSONObject().put("action", JSONObject().put("const", action))).put("required", JSONArray(listOf("path"))))
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", "extensions")
            .put("description", "Discover/load Skills, connect MCP services, or install them from this workspace. install_skill accepts SKILL.md, ZIP or a directory and makes it available across chats. install_mcp reads one {id,name,type,...} JSON object: remote uses an HTTPS url; local uses a command array and optional python_packages/npm_packages. enabled defaults true. Installation does not connect or execute; activate prepares declared dependencies and connects. Users bind credentials on the Run tab; configuration contains no secrets. Existing MCP IDs cannot be overwritten. Inspect list after uncertain installation; installation is not proof of connection or successful invocation. Before creating a Skill, load skill-creator and read its installation reference as needed.")
            .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(listOf("action"))).put("additionalProperties", false).put("oneOf", branches)))
    }
}
