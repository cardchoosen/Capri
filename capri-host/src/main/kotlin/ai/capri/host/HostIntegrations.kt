package ai.capri.host

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** User-managed GitHub, SSH and private-file capabilities for the server kernel. */
internal class HostIntegrations(private val data: Path) {
    private val file = data.resolve("integrations.json")
    private val library = data.resolve("private-library")
    private val sshCredentials = data.resolve("credentials/ssh").toAbsolutePath().normalize()
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).build()

    @Synchronized private fun read(): JSONObject = if (Files.isRegularFile(file, NOFOLLOW_LINKS))
        JSONObject(Files.readString(file)) else JSONObject()

    @Synchronized private fun save(value: JSONObject) {
        Files.createDirectories(data, privateDirectory)
        val temp = Files.createTempFile(data, ".integrations-", ".tmp", privateFile)
        try {
            Files.writeString(temp, value.toString())
            runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
    }

    fun settings(session: String? = null): JSONObject {
        val value = read()
        val github = value.optJSONObject("github") ?: JSONObject()
        val ssh = jsonObjects(value.optJSONArray("ssh"))
        val roots = jsonStrings(value.optJSONArray("privateRoots"))
        val requests = jsonObjects(value.optJSONArray("approvals")).filter { session == null || it.optString("session") == session }.map { request ->
            JSONObject().put("id", request.getString("id")).put("session", request.getString("session"))
                .put("action", request.getString("action")).put("path", request.getString("path"))
                .put("createdAt", request.getLong("createdAt"))
        }
        return JSONObject().put("github", JSONObject().put("connected", github.optString("token").isNotBlank())
            .put("login", github.optString("login")))
            .put("ssh", JSONArray(ssh.map { entry -> JSONObject().put("name", entry.optString("name"))
                .put("host", entry.optString("host")).put("user", entry.optString("user"))
                .put("port", entry.optInt("port", 22)).put("hasIdentityFile", entry.optString("identityFile").isNotBlank()) }))
            .put("privateRoots", JSONArray(roots)).put("approvals", JSONArray(requests))
            .put("library", librarySettings())
    }

    @Synchronized fun librarySettings(): JSONObject {
        Files.createDirectories(library, privateDirectory)
        val files = mutableListOf<JSONObject>()
        Files.walk(library).use { paths ->
            paths.filter { it != library && Files.isRegularFile(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .limit(501).forEach { path -> files.add(JSONObject().put("path", library.relativize(path).toString())
                    .put("size", Files.size(path)).put("updatedAt", Files.getLastModifiedTime(path).toMillis())) }
        }
        return JSONObject().put("path", library.toAbsolutePath().normalize().toString())
            .put("files", JSONArray(files.sortedBy { it.getString("path") }.take(500)))
            .put("truncated", files.size > 500)
    }

    @Synchronized fun importLibraryFile(name: String, bytes: ByteArray): JSONObject {
        require(name.length in 1..180 && name !in setOf(".", "..") && !name.startsWith(".") &&
            !name.any { it == '/' || it == '\\' || it.isISOControl() }) { "Use a plain file name without path separators." }
        require(bytes.isNotEmpty() && bytes.size <= MAX_LIBRARY_UPLOAD) { "A library file must be between 1 byte and 50 MiB." }
        Files.createDirectories(library.resolve("Inbox"), privateDirectory)
        val target = resolveLibrary("Inbox/$name", createParents = true)
        if (Files.exists(target, NOFOLLOW_LINKS)) throw WorkspaceFailure("library_file_exists", "A file with that name already exists in Inbox.")
        writeLibraryFile(target, bytes)
        return JSONObject().put("status", "ok").put("path", library.relativize(target).toString())
            .put("size", bytes.size)
    }

    fun downloadLibraryFile(relative: String): Pair<String, ByteArray> {
        val path = resolveLibrary(relative)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw WorkspaceFailure("library_file_not_found", "The private library file was not found.")
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAX_LIBRARY_UPLOAD + 1) }
        if (bytes.size > MAX_LIBRARY_UPLOAD) throw WorkspaceFailure("library_file_too_large", "The file exceeds the download limit.")
        return path.fileName.toString() to bytes
    }

    fun privateLibraryImage(relative: String): Pair<String, ByteArray> {
        val path = resolveLibrary(relative)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw WorkspaceFailure("library_file_not_found", "The private library image was not found.")
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAX_LIBRARY_VISION_IMAGE + 1) }
        if (bytes.size > MAX_LIBRARY_VISION_IMAGE) throw WorkspaceFailure("private_image_too_large", "Vision inspection is limited to 10 MiB per image.")
        return imageMime(bytes)?.let { it to bytes }
            ?: throw WorkspaceFailure("invalid_private_image", "The file is not a supported image.")
    }

    @Synchronized fun privateLibrary(session: String, taskId: String, workspace: WorkspaceTools,
                                     args: JSONObject, ask: (String) -> Unit): JSONObject {
        val action = args.getString("action")
        Files.createDirectories(library, privateDirectory)
        when (action) {
            "list" -> {
                val path = args.optString("path", ".")
                val roots = privateRootsWithLibrary()
                val scope = "@${library.fileName}" + if (path == ".") "" else "/$path"
                return privateFiles(session, taskId, JSONObject().put("action", "list").put("path", scope), ask)
            }
            "read" -> {
                val path = args.getString("path")
                val target = resolveLibrary(path)
                if (!Files.isRegularFile(target, NOFOLLOW_LINKS) || Files.isSymbolicLink(target))
                    throw WorkspaceFailure("library_file_not_found", "The private library file was not found.")
                if (!consumeGrant(session, taskId, "read", target)) {
                    val pending = newApproval(session, taskId, "read", target, null)
                    ask("Capri is requesting task-specific access to ${target.fileName}. Approve or deny it in the message.")
                    return JSONObject().put("status", "waiting_for_user").put("approval", pending)
                }
                val bytes = Files.newInputStream(target, NOFOLLOW_LINKS).use { it.readNBytes(MAX_LIBRARY_UPLOAD + 1) }
                if (bytes.size > MAX_LIBRARY_UPLOAD) throw WorkspaceFailure("library_file_too_large", "The file exceeds the processing limit.")
                val extension = target.fileName.toString().substringAfterLast('.', "").lowercase()
                return when (extension) {
                    "pdf" -> extractPdf(bytes, args.optInt("firstPage", 1), args.optInt("pageLimit", 5))
                        .put("path", path).put("status", "ok")
                    "docx" -> extractDocx(target).put("path", path).put("status", "ok")
                    "png", "jpg", "jpeg", "gif", "webp" -> {
                        val mime = imageMime(bytes) ?: throw WorkspaceFailure("invalid_private_image", "The file contents do not match a supported image format.")
                        if (bytes.size > MAX_LIBRARY_VISION_IMAGE) throw WorkspaceFailure("private_image_too_large", "Vision inspection is limited to 10 MiB per image.")
                        JSONObject().put("status", "ok").put("path", path).put("visionImage", path).put("mime", mime)
                    }
                    else -> {
                        val text = try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
                        catch (_: Exception) { throw WorkspaceFailure("private_file_not_text", "This file is not valid UTF-8 text; use a supported PDF, DOCX, or image file.") }
                        JSONObject().put("status", "ok").put("path", path).put("content", text.take(65_536))
                            .put("truncated", text.length > 65_536)
                    }
                }
            }
            "import" -> {
                val source = args.getString("source")
                if (!source.startsWith("inputs/")) throw WorkspaceFailure("library_import_source", "Import only user-uploaded conversation files from inputs/.")
                val (name, bytes) = workspace.download(source)
                val destination = args.optString("path").ifBlank { "Inbox/$name" }
                val target = resolveLibrary(destination, createParents = true)
                if (Files.exists(target, NOFOLLOW_LINKS)) throw WorkspaceFailure("library_file_exists", "The destination already exists in the private library.")
                writeLibraryFile(target, bytes)
                return JSONObject().put("status", "ok").put("operation", "import_copy")
                    .put("source", source).put("path", library.relativize(target).toString()).put("size", bytes.size)
            }
            "mkdir" -> {
                val target = resolveLibrary(args.getString("path"), createParents = true)
                Files.createDirectories(target, privateDirectory)
                return JSONObject().put("status", "ok").put("path", library.relativize(target).toString())
            }
            "move", "rename" -> {
                val source = resolveLibrary(args.getString("path"))
                val destination = resolveLibrary(args.getString("destination"), createParents = true)
                require(Files.isRegularFile(source, NOFOLLOW_LINKS) && !Files.isSymbolicLink(source))
                if (Files.exists(destination, NOFOLLOW_LINKS)) throw WorkspaceFailure("library_file_exists", "The destination already exists in the private library.")
                Files.move(source, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                return JSONObject().put("status", "ok").put("operation", action)
                    .put("from", library.relativize(source).toString()).put("to", library.relativize(destination).toString())
            }
            "delete" -> {
                val target = resolveLibrary(args.getString("path"))
                if (!Files.isRegularFile(target, NOFOLLOW_LINKS) || Files.isSymbolicLink(target))
                    throw WorkspaceFailure("library_file_not_found", "The private library file was not found.")
                Files.delete(target)
                return JSONObject().put("status", "ok").put("deleted", library.relativize(target).toString())
            }
            else -> throw WorkspaceFailure("invalid_library_action", "Choose list, read, import, mkdir, move, rename, or delete.")
        }
    }

    fun availableProviders(): List<String> {
        Files.createDirectories(library, privateDirectory)
        val state = read()
        return listOfNotNull(
            "github".takeIf { state.optJSONObject("github")?.optString("token").orEmpty().isNotBlank() },
            "ssh".takeIf { (state.optJSONArray("ssh")?.length() ?: 0) > 0 },
            "private_files".takeIf { (state.optJSONArray("privateRoots")?.length() ?: 0) > 0 },
            "private_library")
    }

    fun configureGitHub(token: String?) {
        val state = read()
        if (token.isNullOrBlank()) {
            state.remove("github")
            save(state)
            return
        }
        require(token.length <= 500 && token.matches(Regex("[A-Za-z0-9_\\-]+"))) { "Invalid GitHub token format." }
        val request = Request.Builder().url("https://api.github.com/user")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $token").get().build()
        val profile = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw WorkspaceFailure("github_auth_failed", "GitHub rejected the token (HTTP ${response.code}).")
            JSONObject(checkNotNull(response.body).string())
        }
        state.put("github", JSONObject().put("token", token).put("login", profile.optString("login")))
        save(state)
    }

    @Synchronized fun configureSsh(entries: JSONArray) {
        require(entries.length() <= 20) { "Configure at most 20 SSH servers." }
        val previous = jsonObjects(read().optJSONArray("ssh"))
        val saved = JSONArray()
        for (entry in jsonObjects(entries)) {
            val name = entry.optString("name").trim()
            val host = entry.optString("host").trim()
            val user = entry.optString("user").trim()
            val port = entry.optInt("port", 22)
            val identityValue = entry.optString("identityFile").trim().ifEmpty {
                if (entry.optBoolean("keepIdentityFile"))
                    previous.firstOrNull { it.optString("name") == name }?.optString("identityFile").orEmpty()
                else ""
            }
            var identity = ""
            require(name.matches(Regex("[A-Za-z0-9_-]{1,40}")) && host.matches(Regex("[A-Za-z0-9._:-]{1,253}")) &&
                user.matches(Regex("[A-Za-z0-9._-]{1,64}")) && port in 1..65535 &&
                (identityValue.isEmpty() || Path.of(if (identityValue.startsWith("~/")) System.getProperty("user.home") + identityValue.drop(1) else identityValue).isAbsolute && identityValue.length <= 1024)) {
                "Each SSH entry needs a valid name, host, user and port; an SSH identity must be a valid file path."
            }
            val imported = entry.optString("identityKey").takeIf(String::isNotBlank)
            identity = if (imported != null) {
                val bytes = try { java.util.Base64.getDecoder().decode(imported) }
                catch (_: IllegalArgumentException) { throw WorkspaceFailure("invalid_ssh_key", "The uploaded SSH key is not valid Base64 text.") }
                storeSshKey(name, bytes)
            } else if (identityValue.isNotEmpty()) {
                val source = Path.of(if (identityValue.startsWith("~/")) System.getProperty("user.home") + identityValue.drop(1) else identityValue)
                    .toAbsolutePath().normalize()
                if (source.startsWith(sshCredentials)) {
                    if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, NOFOLLOW_LINKS))
                        throw WorkspaceFailure("ssh_key_unavailable", "The Capri-managed SSH key file is unavailable.")
                    source.toString()
                } else {
                    if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, NOFOLLOW_LINKS) || Files.size(source) > MAX_SSH_KEY_BYTES)
                        throw WorkspaceFailure("ssh_key_unavailable", "Choose a regular SSH private-key file no larger than 128 KiB.")
                    storeSshKey(name, Files.readAllBytes(source))
                }
            } else ""
            saved.put(JSONObject().put("name", name).put("host", host).put("user", user)
                .put("port", port).put("identityFile", identity))
        }
        require(jsonObjects(saved).map { it.getString("name") }.distinct().size == saved.length()) {
            "SSH server names must be unique."
        }
        val state = read().put("ssh", saved)
        save(state)
        val retained = jsonObjects(saved).map { it.optString("identityFile") }.toSet()
        previous.map { it.optString("identityFile") }.filter { it.isNotBlank() &&
            Path.of(it).toAbsolutePath().normalize().startsWith(sshCredentials) && it !in retained
        }.distinct().forEach { runCatching { Files.deleteIfExists(Path.of(it)) } }
    }

    private fun storeSshKey(name: String, bytes: ByteArray): String {
        if (bytes.isEmpty() || bytes.size > MAX_SSH_KEY_BYTES)
            throw WorkspaceFailure("ssh_key_too_large", "An SSH private key must be at most 128 KiB.")
        val decoded = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw WorkspaceFailure("invalid_ssh_key", "The SSH private key must be UTF-8 PEM text.") }
        if ('\u0000' in decoded || !decoded.trim().matches(Regex("(?s)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----\\s+.+?\\s+-----END [A-Z0-9 ]*PRIVATE KEY-----\\s*")))
            throw WorkspaceFailure("invalid_ssh_key", "The uploaded file is not a supported PEM or OpenSSH private key.")
        Files.createDirectories(data, privateDirectory)
        if (listOf(sshCredentials.parent.parent, sshCredentials.parent, sshCredentials).any {
                Files.exists(it, NOFOLLOW_LINKS) && Files.isSymbolicLink(it)
            })
            throw WorkspaceFailure("unsafe_ssh_key_directory", "The Capri SSH key directory cannot be a symbolic link.")
        Files.createDirectories(sshCredentials, privateDirectory)
        runCatching { Files.setPosixFilePermissions(sshCredentials.parent.parent, PosixFilePermissions.fromString("rwx------")) }
        runCatching { Files.setPosixFilePermissions(sshCredentials.parent, PosixFilePermissions.fromString("rwx------")) }
        runCatching { Files.setPosixFilePermissions(sshCredentials, PosixFilePermissions.fromString("rwx------")) }
        val target = sshCredentials.resolve("$name.pem")
        val temp = Files.createTempFile(sshCredentials, ".$name-", ".tmp", privateFile)
        try {
            Files.write(temp, bytes)
            runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            runCatching { Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------")) }
        } finally { Files.deleteIfExists(temp) }
        return target.toString()
    }

    fun configurePrivateRoots(values: JSONArray) {
        require(values.length() <= 30) { "Configure at most 30 private-file roots." }
        val roots = JSONArray()
        for (raw in jsonStrings(values)) {
            val source = Path.of(raw.trim())
            require(source.isAbsolute) { "Private-file folders must use absolute paths." }
            val path = source.normalize()
            require(path.toString().length <= 2048 && Files.isDirectory(path, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path)) { "Every private-file root must be an existing directory without a symlink root." }
            if (jsonStrings(roots).none { Path.of(it).toRealPath() == path.toRealPath() }) roots.put(path.toRealPath().toString())
        }
        require(jsonStrings(roots).map { Path.of(it).fileName.toString() }.distinct().size == roots.length()) {
            "Allowed folders must have distinct final directory names so Capri can address each one."
        }
        require(jsonStrings(roots).none { Path.of(it).fileName.toString() == library.fileName.toString() }) {
            "An allowed folder cannot use the reserved private-library name."
        }
        save(read().put("privateRoots", roots))
    }

    fun github(args: JSONObject): JSONObject {
        val github = read().optJSONObject("github") ?: throw WorkspaceFailure("github_not_connected", "Connect GitHub in Settings first.")
        val action = args.getString("action")
        val owner = args.optString("owner")
        val repo = args.optString("repo")
        if (action in setOf("search_repositories", "search_code")) require(args.getString("query").isNotBlank())
        if (action == "issues") require(args.optString("state", "open") in setOf("open", "closed", "all"))
        if (action in setOf("repository", "branches", "pull_requests", "issues", "read_file", "create_issue",
                "comment_issue", "write_file", "delete_file", "delete_repository")) repoUrl(owner, repo)
        if (action == "pull_requests") require(args.optString("state", "open") in setOf("open", "closed", "all"))
        if (action == "create_issue") require(args.getString("title").isNotBlank())
        if (action in setOf("write_file", "delete_file")) require(args.getString("message").isNotBlank())
        if (action == "delete_file") require(args.getString("sha").isNotBlank())
        if (action == "create_repository") require(args.getString("name").matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        if (action in setOf("comment_issue", "create_pull_request")) require(args.optInt("number", 1) > 0)
        val base = when (action) {
            "profile" -> "https://api.github.com/user"
            "repos" -> "https://api.github.com/user/repos?per_page=100&sort=updated"
            "search_repositories" -> "https://api.github.com/search/repositories?q=" + encode(args.getString("query"))
            "search_code" -> "https://api.github.com/search/code?q=" + encode(args.getString("query"))
            "repository" -> repoUrl(owner, repo)
            "create_repository" -> "https://api.github.com/user/repos"
            "delete_repository" -> repoUrl(owner, repo)
            "branches" -> repoUrl(owner, repo) + "/branches?per_page=100"
            "pull_requests" -> repoUrl(owner, repo) + "/pulls?state=" + encode(args.optString("state").ifBlank { "open" })
            "issues" -> repoUrl(owner, repo) + "/issues?state=" + (args.optString("state").ifBlank { "open" })
            "read_file" -> repoUrl(owner, repo) + "/contents/" + encodePath(args.getString("path"))
            "create_issue" -> repoUrl(owner, repo) + "/issues"
            "comment_issue" -> repoUrl(owner, repo) + "/issues/${args.getInt("number")}/comments"
            "create_pull_request" -> repoUrl(owner, repo) + "/pulls"
            "write_file" -> repoUrl(owner, repo) + "/contents/" + encodePath(args.getString("path"))
            "delete_file" -> repoUrl(owner, repo) + "/contents/" + encodePath(args.getString("path"))
            else -> throw WorkspaceFailure("invalid_github_action", "Choose a supported GitHub action.")
        }
        val method = when (action) {
            "create_repository", "create_issue", "comment_issue", "create_pull_request" -> "POST"
            "write_file" -> "PUT"
            "delete_repository", "delete_file" -> "DELETE"
            else -> "GET"
        }
        val body = when (action) {
            "create_repository" -> JSONObject().put("name", args.getString("name"))
                .put("private", args.optBoolean("private", true)).put("auto_init", args.optBoolean("autoInit", true))
                .put("description", args.optString("description"))
            "create_issue" -> JSONObject().put("title", args.getString("title")).put("body", args.optString("body"))
            "comment_issue" -> JSONObject().put("body", args.getString("body"))
            "create_pull_request" -> JSONObject().put("title", args.getString("title")).put("head", args.getString("head"))
                .put("base", args.getString("base")).put("body", args.optString("body"))
            "write_file" -> JSONObject().put("message", args.getString("message"))
                .put("content", java.util.Base64.getEncoder().encodeToString(args.getString("content").toByteArray()))
                .putOpt("sha", args.optString("sha").takeIf(String::isNotBlank))
            "delete_file" -> JSONObject().put("message", args.getString("message")).put("sha", args.getString("sha"))
            else -> null
        }
        val request = Request.Builder().url(base).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("Authorization", "Bearer ${github.getString("token")}")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType())
                ?: if (method == "DELETE") "{}".toRequestBody("application/json".toMediaType()) else null).build()
        return http.newCall(request).execute().use { response ->
            val text = checkNotNull(response.body).string()
            if (!response.isSuccessful) throw WorkspaceFailure("github_http_${response.code}", "GitHub request failed: ${text.take(500)}")
            val result = if (text.isBlank()) JSONObject().put("statusCode", response.code) else parseGitHubResult(text)
            if (action == "read_file" && result is JSONObject && result.optString("encoding") == "base64") {
                val encoded = result.optString("content").replace("\\n", "").replace("\n", "")
                val decoded = runCatching { java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8) }.getOrElse { "" }
                result.put("content", decoded.take(100_000)).put("truncated", decoded.length > 100_000)
            }
            JSONObject().put("status", "ok").put("action", action).put("result", result)
        }
    }

    fun ssh(args: JSONObject): JSONObject {
        val action = args.getString("action")
        val configured = jsonObjects(read().optJSONArray("ssh"))
        if (action == "servers") return JSONObject().put("status", "ok").put("servers", JSONArray(configured.map {
            JSONObject().put("name", it.getString("name")).put("host", it.getString("host"))
                .put("user", it.getString("user")).put("port", it.getInt("port"))
        }))
        val entry = jsonObjects(read().optJSONArray("ssh")).firstOrNull { it.optString("name") == args.getString("server") }
            ?: throw WorkspaceFailure("ssh_server_not_found", "Configure this SSH server in Settings first.")
        val command = when (action) {
            "test" -> "printf CAPRI_SSH_READY"
            "exec" -> args.getString("command").trim()
            else -> throw WorkspaceFailure("invalid_ssh_action", "Choose servers, test, or exec.")
        }
        require(command.isNotEmpty() && command.length <= 8000 && !command.contains('\u0000'))
        val target = "${entry.getString("user")}@${entry.getString("host")}"
        val argv = mutableListOf("ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-p", entry.getInt("port").toString())
        entry.optString("identityFile").takeIf(String::isNotBlank)?.let { argv.addAll(listOf("-i", it)) }
        argv.addAll(listOf(target, command))
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val captured = java.io.ByteArrayOutputStream()
        val reader = Thread {
            process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                var retained = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val keep = minOf(count, 32_001 - retained)
                    if (keep > 0) { captured.write(buffer, 0, keep); retained += keep }
                }
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw WorkspaceFailure("ssh_timeout", "SSH command exceeded 90 seconds.") }
        reader.join(1000)
        val output = captured.toByteArray().toString(Charsets.UTF_8)
        return JSONObject().put("status", if (process.exitValue() == 0) "ok" else "error")
            .put("exitCode", process.exitValue()).put("output", output.take(32_000)).put("truncated", output.length > 32_000)
    }

    @Synchronized fun privateFiles(session: String, taskId: String, args: JSONObject, ask: (String) -> Unit): JSONObject {
        val action = args.getString("action")
        val roots = privateRootsWithLibrary()
        if (action == "roots") return JSONObject().put("status", "ok").put("roots", JSONArray(roots.map { root ->
            JSONObject().put("name", root.fileName.toString()).put("path", root.toString())
        }))
        val relative = args.optString("path")
        val target = resolvePrivate(relative, roots)
        if (action == "list") {
            require(Files.isDirectory(target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(target))
            if (!consumeGrant(session, taskId, "read", target)) {
                val pending = newApproval(session, taskId, "read", target, null)
                pending.put("directory", true)
                ask("Capri is requesting permission to list ${target.fileName ?: target}. Approve or deny it in the message.")
                return JSONObject().put("status", "waiting_for_user").put("approval", pending)
            }
            val entries = Files.list(target).use { stream -> stream.limit(200).map { path ->
                JSONObject().put("name", path.fileName.toString()).put("directory", Files.isDirectory(path, NOFOLLOW_LINKS))
                    .put("size", if (Files.isRegularFile(path, NOFOLLOW_LINKS)) Files.size(path) else JSONObject.NULL)
            }.toList() }
            return JSONObject().put("status", "ok").put("entries", JSONArray(entries))
        }
        if (action == "read") {
            if (!consumeGrant(session, taskId, action, target)) {
                val pending = newApproval(session, taskId, action, target, null)
                ask("Capri is requesting read access to ${target.fileName}. Approve or deny it in the message.")
                return JSONObject().put("status", "waiting_for_user").put("approval", pending)
            }
            require(Files.isRegularFile(target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(target))
            val bytes = Files.newInputStream(target, NOFOLLOW_LINKS).use { it.readNBytes(65_537) }
            if (bytes.size > 65_536) throw WorkspaceFailure("private_file_too_large", "Private-file reads are limited to 64 KiB per call.")
            return JSONObject().put("status", "ok").put("path", relative).put("content", bytes.toString(Charsets.UTF_8))
        }
        if (action in setOf("write", "delete")) {
            val payload = if (action == "write") args.getString("content") else ""
            require(payload.toByteArray().size <= 1_048_576)
            if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS))
                throw WorkspaceFailure("private_file_not_regular", "Only regular files can be changed or deleted.")
            if (action == "delete" && !Files.isRegularFile(target, NOFOLLOW_LINKS))
                throw WorkspaceFailure("private_file_not_found", "The file to delete does not exist.")
            val state = read()
            val grants = state.optJSONArray("changeGrants") ?: JSONArray()
            val grantIndex = (0 until grants.length()).firstOrNull { index ->
                val grant = grants.getJSONObject(index)
                grant.optString("session") == session && grant.optString("taskId") == taskId && grant.optString("action") == action &&
                    grant.optString("path") == target.toString() && grant.optString("content") == payload
            }
            if (grantIndex != null) {
                val grant = grants.getJSONObject(grantIndex)
                validateApprovedTarget(target, roots)
                if (revision(target) != grant.optString("revision"))
                    throw WorkspaceFailure("private_file_changed", "The original file changed after approval. Review the latest version before retrying.")
                if (action == "write") writeAtomically(target, payload)
                else Files.delete(target)
                grants.remove(grantIndex)
                state.put("changeGrants", grants)
                save(state)
                return JSONObject().put("status", "ok").put("action", action).put("path", relative)
                    .put("approved", true)
            }
            val approval = newApproval(session, taskId, action, target, payload)
            ask("Capri requests approval to $action original file ${target.fileName}. Approve or deny it in the message.")
            return JSONObject().put("status", "waiting_for_user").put("approval", approval)
        }
        throw WorkspaceFailure("invalid_private_file_action", "Choose roots, list, read, write, or delete.")
    }

    @Synchronized fun decideApproval(session: String, id: String, approve: Boolean): JSONObject {
        val state = read()
        val requests = state.optJSONArray("approvals") ?: JSONArray()
        val index = (0 until requests.length()).firstOrNull { requests.getJSONObject(it).optString("id") == id }
            ?: throw WorkspaceFailure("approval_not_found", "This approval request is no longer available.")
        val item = requests.getJSONObject(index)
        if (item.getString("session") != session || System.currentTimeMillis() - item.getLong("createdAt") > 30 * 60_000)
            throw WorkspaceFailure("approval_expired", "This approval request has expired.")
        if (approve) {
            if (item.getString("action") == "read") {
                state.optJSONArray("readGrants")?.let { grants ->
                    for (i in 0 until grants.length()) if (grants.getJSONObject(i).optString("id") == id) grants.remove(i)
                }
                val grants = state.optJSONArray("readGrants") ?: JSONArray().also { state.put("readGrants", it) }
                grants.put(JSONObject().put("id", id).put("session", session).put("taskId", item.getString("taskId"))
                    .put("path", item.getString("path"))
                    .put("recursive", item.optBoolean("directory"))
                    .put("createdAt", System.currentTimeMillis()))
            } else {
                val target = Path.of(item.getString("path"))
                val roots = privateRootsWithLibrary(state)
                validateApprovedTarget(target, roots)
                val grants = state.optJSONArray("changeGrants") ?: JSONArray()
                grants.put(JSONObject().put("id", id).put("session", session).put("taskId", item.getString("taskId"))
                    .put("action", item.getString("action"))
                    .put("path", item.getString("path")).put("content", item.optString("content"))
                    .put("revision", item.optString("revision")).put("createdAt", System.currentTimeMillis()))
                state.put("changeGrants", grants)
            }
        }
        requests.remove(index)
        state.put("approvals", requests)
        save(state)
        return JSONObject().put("status", if (approve) "approved" else "denied")
            .put("action", item.getString("action")).put("path", item.getString("path"))
    }

    @Synchronized fun finishTask(session: String, taskId: String) {
        val state = read()
        for (name in listOf("readGrants", "changeGrants")) {
            val grants = state.optJSONArray(name) ?: continue
            for (index in grants.length() - 1 downTo 0) {
                val item = grants.getJSONObject(index)
                if (item.optString("session") == session && item.optString("taskId") == taskId) grants.remove(index)
            }
        }
        save(state)
    }

    private fun newApproval(session: String, taskId: String, action: String, path: Path, content: String?): JSONObject {
        val state = read()
        val requests = state.optJSONArray("approvals") ?: JSONArray()
        requests.put(JSONObject().put("id", UUID.randomUUID().toString()).put("session", session).put("taskId", taskId)
            .put("action", action).put("path", path.toString()).put("content", content ?: JSONObject.NULL)
            .put("directory", Files.isDirectory(path, NOFOLLOW_LINKS))
            .put("revision", if (action == "read") "" else revision(path))
            .put("createdAt", System.currentTimeMillis()))
        state.put("approvals", requests)
        save(state)
        return JSONObject().put("id", requests.getJSONObject(requests.length() - 1).getString("id"))
            .put("action", action).put("path", path.toString()).put("preview", (content ?: "").take(2000))
            .put("previewTruncated", (content?.length ?: 0) > 2000)
            .put("existing", Files.exists(path, NOFOLLOW_LINKS)).put("directory", Files.isDirectory(path, NOFOLLOW_LINKS))
    }

    private fun consumeGrant(session: String, taskId: String, action: String, path: Path): Boolean {
        if (action != "read") return false
        val state = read()
        val grants = state.optJSONArray("readGrants") ?: return false
        val index = (0 until grants.length()).firstOrNull { index ->
            val grant = grants.getJSONObject(index)
            val grantedPath = Path.of(grant.optString("path"))
            val allowedPath = grant.optBoolean("recursive") && path.toAbsolutePath().normalize().startsWith(grantedPath.toAbsolutePath().normalize())
            grant.optString("session") == session &&
                grant.optString("taskId") == taskId && (allowedPath || grant.optString("path") == path.toString()) &&
                System.currentTimeMillis() - grant.optLong("createdAt") <= 30 * 60_000
        } ?: return false
        if (!grants.getJSONObject(index).optBoolean("recursive")) {
            grants.remove(index)
            save(state)
        }
        return true
    }

    private fun privateRootsWithLibrary(state: JSONObject = read()): List<Path> =
        (jsonStrings(state.optJSONArray("privateRoots")).map(Path::of) + library.toAbsolutePath().normalize()).distinct()

    private fun resolveLibrary(relative: String, createParents: Boolean = false): Path {
        require(relative.isNotBlank() && relative.length <= 4096 && !Path.of(relative).isAbsolute &&
            !relative.contains('\\') && !relative.any { it.isISOControl() } && Path.of(relative).none { it.toString() == ".." }) {
            "Use a private-library relative path without parent traversal."
        }
        Files.createDirectories(library, privateDirectory)
        val parts = Path.of(relative).normalize().filter { it.toString().isNotEmpty() }
        if (parts.isEmpty()) return library
        var current = library
        for ((index, part) in parts.withIndex()) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw WorkspaceFailure("library_invalid_path", "Symbolic links are not allowed in the private library.")
            if (createParents && index < parts.lastIndex && Files.notExists(current, NOFOLLOW_LINKS))
                Files.createDirectory(current, privateDirectory)
        }
        val parent = current.parent
        if (parent != null && Files.exists(parent, NOFOLLOW_LINKS) && !parent.toRealPath().startsWith(library.toRealPath()))
            throw WorkspaceFailure("library_invalid_path", "The path is outside the private library.")
        return current
    }

    private fun writeLibraryFile(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent, privateDirectory)
        val temp = Files.createTempFile(target.parent, ".capri-import-", ".tmp", privateFile)
        try {
            Files.write(temp, bytes)
            Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun resolvePrivate(relative: String, roots: List<Path>): Path {
        if (roots.isEmpty()) throw WorkspaceFailure("private_roots_empty", "Add an allowed private-file folder in Settings first.")
        require(relative.isNotBlank() && relative.length <= 4096 && !Path.of(relative).isAbsolute && !relative.contains('\u0000'))
        val normalizedRoots = roots.map { it.toAbsolutePath().normalize() }
        val scoped = if (relative.startsWith("@")) {
            val prefix = relative.removePrefix("@").substringBefore('/')
            val chosen = normalizedRoots.filter { it.fileName.toString() == prefix }
            if (chosen.size != 1) throw WorkspaceFailure("private_root_required", "Prefix the path with '@' and one configured folder name, for example '@${normalizedRoots.first().fileName}/file.txt'.")
            val nested = relative.removePrefix("@").substringAfter('/', "")
            chosen to nested.ifBlank { "." }
        } else if (normalizedRoots.size == 1) normalizedRoots to relative
        else throw WorkspaceFailure("private_root_required", "Prefix the path with '@' and one configured folder name, for example '@${normalizedRoots.first().fileName}/file.txt'.")
        for (root in scoped.first) {
            val candidate = root.resolve(scoped.second).normalize()
            if (!candidate.startsWith(root)) continue
            if (candidate == root) return candidate
            val parent = candidate.parent ?: continue
            if (!Files.exists(parent, NOFOLLOW_LINKS) || Files.isSymbolicLink(parent) ||
                !parent.toRealPath().startsWith(root.toRealPath())) continue
            if (Files.exists(candidate, NOFOLLOW_LINKS) && Files.isSymbolicLink(candidate)) continue
            return candidate
        }
        throw WorkspaceFailure("private_path_outside_roots", "Choose a path inside an allowed private-file folder.")
    }

    private fun validateApprovedTarget(target: Path, roots: List<Path>) {
        val path = target.toAbsolutePath().normalize()
        val root = roots.map { it.toAbsolutePath().normalize() }.firstOrNull { path.startsWith(it) }
            ?: throw WorkspaceFailure("private_path_outside_roots", "This file is no longer inside an allowed folder.")
        val parent = path.parent ?: throw WorkspaceFailure("invalid_private_path", "A file path is required.")
        if (!parent.toRealPath().startsWith(root.toRealPath()) || Files.isSymbolicLink(path))
            throw WorkspaceFailure("private_path_outside_roots", "This file path is no longer safe to access.")
    }

    private fun writeAtomically(target: Path, content: String) {
        val temp = Files.createTempFile(target.parent, ".capri-private-", ".tmp", privateFile)
        try {
            Files.writeString(temp, content)
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun revision(path: Path): String {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return "missing"
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) return "not_regular"
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun repoUrl(owner: String, repo: String): String {
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && repo.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        return "https://api.github.com/repos/$owner/$repo"
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)
    private fun encodePath(value: String) = value.split('/').joinToString("/") { encode(it) }
    private fun parseGitHubResult(text: String): Any = runCatching { JSONObject(text) }.getOrElse {
        runCatching { JSONArray(text) }.getOrElse { text.take(200_000) }
    }

    companion object {
        private const val MAX_SSH_KEY_BYTES = 128 * 1024
        private val privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        const val MAX_LIBRARY_UPLOAD = 50 * 1024 * 1024
        const val MAX_LIBRARY_VISION_IMAGE = 10 * 1024 * 1024
        private fun jsonObjects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        private fun jsonStrings(array: JSONArray?): List<String> = if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }
}
