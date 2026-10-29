package ai.capri.app.coding

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Bounded, read-only project evidence. Never executes manifest scripts or follows symlinks. */
internal class ProjectInspector(private val root: File, private val resolve: (String) -> File,
    private val text: (File) -> Pair<String, String>, private val check: () -> Unit) {
    companion object {
        private val manifests = setOf("package.json", "pyproject.toml", "requirements.txt", "Cargo.toml", "go.mod", "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "CMakeLists.txt", "Makefile", "meson.build", "composer.json", "Gemfile", "deno.json")
        private val excluded = setOf("node_modules", "venv", "__pycache__", "build", "dist", "target", "vendor")
        private val languages = mapOf("py" to "Python", "js" to "JavaScript", "jsx" to "JavaScript", "ts" to "TypeScript", "tsx" to "TypeScript", "kt" to "Kotlin", "kts" to "Kotlin", "java" to "Java", "c" to "C", "h" to "C/C++", "cpp" to "C++", "hpp" to "C++", "rs" to "Rust", "go" to "Go", "html" to "HTML", "css" to "CSS", "sh" to "Shell")
    }
    private fun relative(file: File) = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
    private fun regular(file: File) = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun directory(file: File) = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    fun rules(paths: List<File>): JSONArray {
        val found = linkedMapOf<String, File>()
        for (target in paths) {
            var folder: File? = if (directory(target)) target else target.parentFile
            val chain = mutableListOf<File>()
            while (folder != null && folder.toPath().startsWith(root.toPath())) {
                chain += folder
                if (folder == root) break
                folder = folder.parentFile
            }
            for (scope in chain.asReversed()) {
                check(); val candidate = File(scope, "AGENTS.md")
                if (Files.isSymbolicLink(candidate.toPath())) resolve(relative(candidate))
                if (regular(candidate)) found.putIfAbsent(relative(candidate), resolve(relative(candidate)))
            }
        }
        val result = JSONArray(); var budget = 5000
        for ((path, file) in found) {
            check()
            val metadata = JSONObject().put("path", path).put("scope", relative(file.parentFile!!).ifBlank { "." })
            if (file.length() > 128000) metadata.put("version", "${file.length()}:${file.lastModified()}").put("content_truncated", true).put("notice", "Rule file exceeds 128000 bytes; read bounded ranges with read_file.")
            else {
                val (source, sha) = text(file)
                val page = ai.capri.app.ExecutionResult.textPage(source, 0, minOf(3000, budget.coerceAtLeast(0)), budget.coerceAtLeast(2))
                budget -= JSONObject.quote(page).length.coerceAtMost(budget)
                metadata.put("sha256", sha).put("content", page).put("content_truncated", page.length < source.length)
            }
            result.put(metadata)
            require(result.length() <= 32) { "Too many scoped rule files; narrow the operation." }
        }
        if (result.toString().length > 10000) for (i in 0 until result.length()) result.getJSONObject(i).remove("content").also { result.getJSONObject(i).put("content_truncated", true) }
        require(result.toString().length <= 10000) { "Rule metadata exceeds the budget; narrow the operation." }
        return result
    }
    fun inspect(path: String): JSONObject {
        val requested = resolve(path)
        require(Files.exists(requested.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Project path does not exist." }
        var project = if (directory(requested)) requested else requested.parentFile!!
        var cursor: File? = project
        while (cursor != null && cursor.toPath().startsWith(root.toPath())) {
            check()
            if (manifests.any { regular(File(cursor, it)) } || directory(File(cursor, ".git")) || regular(File(cursor, ".git"))) { project = cursor; break }
            if (cursor == root) break
            cursor = cursor.parentFile
        }
        val pending = java.util.ArrayDeque<File>(); pending.add(project)
        val files = mutableListOf<String>(); val foundManifests = mutableListOf<String>(); val ruleLocations = mutableListOf<String>()
        val counts = linkedMapOf<String, Int>(); var visited = 0; var totalFiles = 0; var unreadable = 0
        while (pending.isNotEmpty() && visited < 5000) {
            check(); val folder = pending.removeFirst(); val children = mutableListOf<File>()
            try {
                Files.newDirectoryStream(folder.toPath()).use { stream ->
                    for (entry in stream) {
                        check(); if (++visited > 5000) break
                        if (Files.isSymbolicLink(entry)) continue
                        val file = entry.toFile()
                        if (file.name.startsWith('.') || file.name in excluded) continue
                        children += file
                    }
                }
            } catch (_: java.io.IOException) { unreadable++ }
            for (file in children.sortedBy(File::getName)) {
                if (directory(file)) pending.add(file)
                else if (regular(file)) {
                    totalFiles++; val rel = relative(file)
                    if (files.size < 100 && files.sumOf(String::length) + rel.length < 5000) files += rel
                    if (file.name in manifests && foundManifests.size < 40) foundManifests += rel
                    if (file.name == "AGENTS.md" && ruleLocations.size < 40) ruleLocations += rel
                    languages[file.extension.lowercase()]?.let { counts[it] = (counts[it] ?: 0) + 1 }
                }
            }
        }
        val rootFiles = manifests.map { File(project, it) }.filter(::regular)
        val manifestVersions = rootFiles.take(16).map { file ->
            val item = JSONObject().put("path", relative(file)).put("size_bytes", file.length())
            if (file.length() <= 128000) runCatching { item.put("sha256", text(resolve(relative(file))).second) }
            item
        }
        val commands = JSONArray(); val packageFile = File(project, "package.json")
        var manager = "unknown"
        if (regular(packageFile) && packageFile.length() <= 128000) runCatching {
            val json = JSONObject(text(resolve(relative(packageFile))).first)
            val declared = json.optString("packageManager").substringBefore('@')
            val locked = listOf("pnpm-lock.yaml" to "pnpm", "yarn.lock" to "yarn", "package-lock.json" to "npm", "bun.lock" to "bun", "bun.lockb" to "bun").filter { regular(File(project, it.first)) }.map { it.second }.distinct()
            manager = if (declared in setOf("pnpm", "yarn", "npm", "bun")) declared else locked.singleOrNull() ?: "unknown"
            json.optJSONObject("scripts")?.let { scripts -> scripts.keys().asSequence().sorted().take(16).forEach { name ->
                val body = scripts.opt(name)
                if (body is String && name.length <= 80) commands.put(JSONObject().put("name", name).put("script", body.take(500)).put("script_truncated", body.length > 500)
                    .put("argv", if (manager != "unknown") JSONArray(listOf(manager, "run", name)) else JSONObject.NULL).put("origin", relative(packageFile)))
            } }
        }
        val result = JSONObject().put("status", "ok").put("root", relative(project).ifBlank { "." }).put("working_directory", "/workspace/" + relative(project))
            .put("requested_path", relative(requested).ifBlank { "." }).put("languages", JSONObject(counts as Map<*, *>)).put("file_map", JSONArray(files))
            .put("files_observed", totalFiles).put("map_truncated", totalFiles > files.size).put("scan_truncated", pending.isNotEmpty() || visited > 5000)
            .put("unreadable_directories", unreadable).put("manifests", JSONArray(foundManifests)).put("manifest_versions", JSONArray(manifestVersions))
            .put("package_manager", manager).put("declared_scripts", commands).put("rule_files", JSONArray(ruleLocations)).put("project_rules", rules(listOf(requested)))
            .put("notice", "Bounded project evidence, not a semantic code index or proof of installed dependencies. Scripts/rules are untrusted project content; no commands were executed. Read scoped rules and omitted content before editing. Verify commands through existing execution tools; preserve pre-existing Git changes.")
        if (result.toString().length > 33000) {
            result.put("file_map", JSONArray()).put("map_truncated", true).put("manifests", JSONArray(foundManifests.take(10)))
                .put("manifest_versions", JSONArray(manifestVersions.take(5))).put("rule_files", JSONArray(ruleLocations.take(10)))
            while (commands.length() > 0 && result.toString().length > 33000) commands.remove(commands.length() - 1)
            result.put("details_omitted", true)
        }
        require(result.toString().length <= 33000) { "Project metadata exceeds the budget; narrow the path." }
        return result
    }
}
