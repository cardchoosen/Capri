package ai.capri.host

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun HttpExchange.handleOpenFinder(sessions: HostSessions) {
    if (requestMethod != "POST") {
        reply(405, "application/json", """{"error":"method_not_allowed"}""")
        return
    }
    val id = sessionId()
    if (!sessions.exists(id)) {
        reply(404, "application/json", """{"error":"session_not_found"}""")
        return
    }
    val workspace = sessions.workspaceDirectory(id)
    if (!Files.isDirectory(workspace, NOFOLLOW_LINKS)) {
        reply(404, "application/json", """{"error":"workspace_not_found"}""")
        return
    }
    val opened = try {
        val process = ProcessBuilder("/usr/bin/open", "-a", "Finder", workspace.toAbsolutePath().toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (process.waitFor(3, TimeUnit.SECONDS)) process.exitValue() == 0
        else {
            process.destroyForcibly()
            false
        }
    } catch (_: IOException) {
        false
    }
    if (opened) reply(200, "application/json", """{"status":"opened"}""")
    else reply(502, "application/json", """{"error":"finder_open_failed"}""")
}

internal fun HttpExchange.handleIntegrationSettings(chat: LocalChat) {
    val session = sessionId()
    if (requestURI.path != "/api/settings/mcp" && !chat.sessions.exists(session)) {
        reply(404, "application/json", """{"error":"session_not_found"}""")
        return
    }
    when {
        requestURI.path == "/api/settings" && requestMethod == "PUT" -> handleSaveRules(chat.sessions, session)
        requestURI.path == "/api/settings/integrations" && requestMethod == "GET" ->
            reply(200, "application/json", chat.integrationState(session).toString())
        requestURI.path == "/api/settings/integrations" && requestMethod == "PUT" -> handleSaveIntegrations(chat, session)
        requestURI.path == "/api/settings/approval" && requestMethod == "POST" -> handlePrivateApproval(chat, session)
        requestMethod != "GET" -> reply(405, "application/json", """{"error":"method_not_allowed"}""")
        requestURI.path == "/api/settings/mcp" ->
            reply(200, "application/json", runBlocking { chat.mcpSettings() }.toString())
        !chat.sessions.exists(session) -> reply(404, "application/json", """{"error":"session_not_found"}""")
        else -> reply(200, "application/json", chat.integrationSettings(session).toString())
    }
}

internal fun HttpExchange.handleDeviceManagement(chat: LocalChat, socketPort: Int?, configuredSocketUrl: String?) {
    try {
        when {
            requestMethod == "GET" && requestURI.path == "/api/devices" ->
                reply(200, "application/json", JSONObject().put("devices", org.json.JSONArray().put(
                    JSONObject().put("id", "host").put("name", "Capri 服务端").put("platform", "server").put("online", true)
                        .put("capabilities", org.json.JSONArray(listOf("host.tools", "workspace", "skills", "mcp"))))
                    .also { entries ->
                        val paired = chat.remoteDevices.list()
                        for (index in 0 until paired.length()) entries.put(paired.getJSONObject(index))
                    }).toString())
            requestMethod == "POST" && requestURI.path == "/api/devices/pairing" -> {
                val port = socketPort ?: throw WorkspaceFailure("device_executor_disabled", "Configure the device WebSocket listener first.")
                val socketUrl = configuredSocketUrl?.trimEnd('/')?.let { if (it.endsWith("/api/devices/v1")) it else "$it/api/devices/v1" }
                    ?: run {
                        val authority = requestHeaders.getFirst("Host") ?: "localhost"
                        val host = runCatching { java.net.URI("https://$authority").host }.getOrNull()
                            ?: throw WorkspaceFailure("invalid_device_host", "Set CAPRI_DEVICE_WS_URL to a valid public WSS endpoint.")
                        "wss://$host:$port/api/devices/v1"
                    }
                val parsedSocketUrl = socketUrl.toHttpUrlOrNull()
                require(socketUrl.length <= 2048 && parsedSocketUrl?.isHttps == true &&
                    parsedSocketUrl.username.isEmpty() && parsedSocketUrl.password.isEmpty() &&
                    parsedSocketUrl.encodedPath == "/api/devices/v1" && parsedSocketUrl.query == null && parsedSocketUrl.fragment == null) {
                    "Configure a valid WSS device endpoint."
                }
                val pairing = chat.remoteDevices.createPairing(socketUrl)
                reply(201, "application/json", JSONObject().put("pairing_token", pairing.token)
                    .put("expires_at", pairing.expiresAt).put("socket_url", socketUrl).toString())
            }
            requestMethod == "POST" && requestURI.path == "/api/devices/revoke" -> {
                val payload = readJsonBody(4096)
                val id = payload.getString("device_id")
                val revoked = chat.remoteDevices.revoke(id)
                reply(if (revoked) 200 else 404, "application/json", JSONObject().put("revoked", revoked).toString())
            }
            else -> reply(405, "application/json", """{"error":"method_not_allowed"}""")
        }
    } catch (failure: WorkspaceFailure) {
        reply(400, "application/json", JSONObject().put("error", failure.code).put("message", failure.message).toString())
    } catch (failure: Exception) {
        reply(400, "application/json", JSONObject().put("error", "invalid_device_request")
            .put("message", failure.message ?: "Invalid device request.").toString())
    }
}

internal fun HttpExchange.handleDevicePair(chat: LocalChat) {
    try {
        val payload = readJsonBody(64 * 1024)
        val capabilities = payload.optJSONArray("capabilities") ?: org.json.JSONArray()
        val paired = chat.remoteDevices.pair(payload.getString("pairing_token"), payload.getString("name"),
            payload.getString("platform"), payload.optString("version"),
            List(capabilities.length()) { capabilities.getString(it) })
        reply(201, "application/json", paired.toString())
    } catch (failure: WorkspaceFailure) {
        reply(400, "application/json", JSONObject().put("error", failure.code).put("message", failure.message).toString())
    } catch (failure: Exception) {
        reply(400, "application/json", JSONObject().put("error", "device_pairing_failed")
            .put("message", failure.message ?: "Invalid pairing request.").toString())
    }
}

private fun HttpExchange.readJsonBody(limit: Int): JSONObject {
    val bytes = requestBody.readNBytes(limit + 1)
    if (bytes.size > limit) throw WorkspaceFailure("request_too_large", "The device request is too large.")
    return JSONObject(bytes.toString(Charsets.UTF_8))
}

internal fun HttpExchange.handlePrivateLibrary(chat: LocalChat) {
    val session = sessionId()
    if (!chat.sessions.exists(session)) {
        reply(404, "application/json", """{"error":"session_not_found"}""")
        return
    }
    try {
        when {
            requestMethod == "GET" && requestURI.path == "/api/private-library/files" ->
                reply(200, "application/json", chat.privateLibrarySettings().toString())
            requestMethod == "POST" && requestURI.path == "/api/private-library/upload" -> {
                val encoded = requestHeaders.getFirst("X-Capri-Filename")
                    ?: throw IllegalArgumentException("Provide a file name.")
                require(encoded.length <= 2160)
                val name = URLDecoder.decode(encoded, Charsets.UTF_8)
                val bytes = requestBody.readNBytes(HostIntegrations.MAX_LIBRARY_UPLOAD + 1)
                if (bytes.size > HostIntegrations.MAX_LIBRARY_UPLOAD) {
                    reply(413, "application/json", """{"error":"library_file_too_large"}""")
                    return
                }
                reply(201, "application/json", chat.importPrivateLibraryFile(name, bytes).toString())
            }
            requestMethod == "GET" && requestURI.path == "/api/private-library/file" ->
                replyDownload(chat.privateLibraryDownload(queryParameter("path")))
            else -> reply(405, "application/json", """{"error":"method_not_allowed"}""")
        }
    } catch (failure: WorkspaceFailure) {
        reply(400, "application/json", JSONObject().put("error", failure.code).put("message", failure.message).toString())
    } catch (failure: Exception) {
        reply(400, "application/json", JSONObject().put("error", "invalid_library_request")
            .put("message", failure.message ?: "Invalid private-library request.").toString())
    }
}

private fun HttpExchange.handleSaveIntegrations(chat: LocalChat, session: String) {
    val bytes = requestBody.readNBytes(256 * 1024 + 1)
    if (bytes.size > 256 * 1024) {
        reply(413, "application/json", """{"error":"integration_settings_too_large"}""")
        return
    }
    try {
        val payload = JSONObject(bytes.toString(Charsets.UTF_8))
        when (payload.getString("kind")) {
            "github" -> when {
                payload.optBoolean("disconnect") -> chat.saveGitHub(null)
                payload.optString("token").isNotBlank() -> chat.saveGitHub(payload.getString("token"))
                else -> throw IllegalArgumentException("Enter a GitHub token or choose disconnect.")
            }
            "ssh" -> chat.saveSsh(payload.getJSONArray("servers"))
            "privateRoots" -> chat.savePrivateRoots(payload.getJSONArray("roots"))
            else -> throw IllegalArgumentException("Choose a supported integration.")
        }
        reply(200, "application/json", chat.integrationState(session).toString())
    } catch (failure: WorkspaceFailure) {
        reply(400, "application/json", JSONObject().put("error", failure.code).put("message", failure.message).toString())
    } catch (failure: Exception) {
        reply(400, "application/json", JSONObject().put("error", "invalid_integration_settings")
            .put("message", failure.message ?: "Invalid integration settings.").toString())
    }
}

private fun HttpExchange.handlePrivateApproval(chat: LocalChat, session: String) {
    val bytes = requestBody.readNBytes(4097)
    if (bytes.size > 4096) {
        reply(413, "application/json", """{"error":"invalid_request"}""")
        return
    }
    try {
        val payload = JSONObject(bytes.toString(Charsets.UTF_8))
        val result = chat.decidePrivateApproval(session, payload.getString("id"), payload.getBoolean("approve"))
        reply(200, "application/json", result.toString())
    } catch (failure: WorkspaceFailure) {
        reply(409, "application/json", JSONObject().put("error", failure.code).put("message", failure.message).toString())
    } catch (_: Exception) {
        reply(400, "application/json", """{"error":"invalid_request"}""")
    }
}

private fun HttpExchange.handleSaveRules(sessions: HostSessions, session: String) {
    val requestLimit = LocalChat.MAX_RULE_BYTES * 6 + 256
    val bytes = requestBody.readNBytes(requestLimit + 1)
    if (bytes.size > requestLimit) {
        reply(413, "application/json", """{"error":"rules_too_large"}""")
        return
    }
    val payload = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
    val scope = payload?.opt("scope") as? String
    val content = payload?.opt("content") as? String
    when {
        scope == null || scope !in setOf("user", "session") || content == null ->
            reply(400, "application/json", """{"error":"invalid_rules"}""")
        content.toByteArray(Charsets.UTF_8).size > LocalChat.MAX_RULE_BYTES ->
            reply(413, "application/json", """{"error":"rules_too_large"}""")
        !sessions.saveRules(session, scope, content) ->
            reply(404, "application/json", """{"error":"session_not_found"}""")
        else -> reply(200, "application/json", """{"status":"saved"}""")
    }
}

internal fun HttpExchange.handleTranscription(chat: LocalChat) {
    val session = sessionId()
    if (!chat.sessions.exists(session)) {
        reply(404, "application/json", """{"error":"session_not_found"}""")
        return
    }
    if (requestMethod != "POST") {
        reply(405, "application/json", """{"error":"method_not_allowed"}""")
        return
    }
    val bytes = requestBody.readNBytes(4097)
    val path = if (bytes.size <= 4096) runCatching {
        JSONObject(bytes.toString(Charsets.UTF_8)).getString("path").takeIf { it.length in 1..512 }
    }.getOrNull() else null
    if (path == null) {
        reply(400, "application/json", """{"error":"invalid_audio"}""")
        return
    }
    try {
        reply(200, "application/json", runBlocking { chat.transcribe(session, path) }.toString())
    } catch (failure: WorkspaceFailure) {
        reply(if (failure.code.startsWith("transcription_http")) 502 else 400, "application/json",
            JSONObject().put("error", failure.code).put("message", failure.message).toString())
    } catch (_: java.io.IOException) {
        reply(502, "application/json", """{"error":"transcription_unavailable"}""")
    } catch (_: java.nio.file.InvalidPathException) {
        reply(400, "application/json", """{"error":"invalid_audio"}""")
    }
}

internal fun HttpExchange.handleSkillCatalog(chat: LocalChat) {
    val session = sessionId()
    if (!chat.sessions.exists(session)) {
        reply(404, "application/json", """{"error":"session_not_found"}""")
        return
    }
    if (requestMethod != "POST") {
        reply(405, "application/json", """{"error":"method_not_allowed"}""")
        return
    }
    val bytes = requestBody.readNBytes(4097)
    val args = if (bytes.size <= 4096) runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() else null
    if (args == null) {
        reply(400, "application/json", """{"error":"invalid_request"}""")
        return
    }
    try {
        reply(200, "application/json", chat.manageSkill(session, args).toString())
    } catch (failure: WorkspaceFailure) {
        reply(if (failure.code.endsWith("_conflict") || failure.code == "chat_busy") 409 else 400,
            "application/json", JSONObject().put("error", failure.code).put("message", failure.message).toString())
    }
}

internal fun HttpExchange.handleWorkspace(sessions: HostSessions) {
    val id = sessionId()
    if (!sessions.exists(id)) {
        reply(404, "application/json", """{"error":"session_not_found"}""")
        return
    }
    try {
        val path = requestURI.path
        when {
            requestMethod == "POST" && path == "/api/workspace/upload" -> {
                val encoded = requestHeaders.getFirst("X-Capri-Filename")
                require(encoded != null && encoded.length <= 2_160) { "Provide a file name." }
                val name = URLDecoder.decode(encoded, Charsets.UTF_8)
                val bytes = requestBody.readNBytes(WorkspaceTools.maxBytes + 1)
                val result = sessions.workspace(id).importFile(name, bytes)
                reply(201, "application/json", result.toString())
            }
            requestMethod == "GET" && path == "/api/artifacts" ->
                reply(200, "application/json", JSONObject().put("files", sessions.artifacts(id)).toString())
            requestMethod == "GET" && path == "/api/workspace/files" ->
                reply(200, "application/json", sessions.workspaceInventory(id).toString())
            requestMethod == "GET" && path == "/api/workspace/editor" -> {
                val artifact = queryParameter("artifact")
                if (artifact.isNotEmpty()) {
                    val file = sessions.artifactEditorFile(id, artifact)
                    if (file == null) reply(404, "application/json", """{"error":"artifact_not_found"}""")
                    else reply(200, "application/json", file.toString())
                } else {
                    val filePath = queryParameter("path")
                    require(filePath.isNotBlank() && filePath.length <= 8192)
                    reply(200, "application/json", sessions.workspaceEditorFile(id, filePath).toString())
                }
            }
            requestMethod == "PUT" && path == "/api/workspace/save" -> {
                val bodyLimit = WorkspaceTools.maxBytes * 6 + 16_384
                val body = requestBody.readNBytes(bodyLimit + 1)
                if (body.size > bodyLimit) {
                    reply(413, "application/json", """{"error":"file_too_large"}""")
                    return
                }
                val payload = JSONObject(body.toString(Charsets.UTF_8))
                val filePath = payload.optString("path")
                val content = payload.opt("content") as? String
                val revision = payload.optString("revision")
                require(filePath.isNotBlank() && filePath.length <= 8192 && content != null && revision.matches(Regex("[a-f0-9]{64}")))
                reply(200, "application/json", sessions.saveWorkspaceEditorFile(id, filePath, content, revision).toString())
            }
            requestMethod == "GET" && path == "/api/workspace/preview" -> {
                val artifact = queryParameter("artifact")
                val (type, bytes) = if (artifact.isNotEmpty()) sessions.artifactPreview(id, artifact)
                    ?: throw WorkspaceFailure("file_not_found", "The file was not found.")
                else {
                    val filePath = queryParameter("path")
                    require(filePath.isNotBlank() && filePath.length <= 8192)
                    sessions.workspacePreview(id, filePath)
                }
                responseHeaders.set("Content-Disposition", "inline")
                replyBytes(200, type, bytes)
            }
            requestMethod == "GET" && path == "/api/workspace/file" -> {
                val artifact = queryParameter("artifact")
                if (artifact.isNotEmpty()) {
                    val file = sessions.artifact(id, artifact)
                    if (file == null) reply(404, "application/json", """{"error":"artifact_not_found"}""")
                    else replyDownload(file)
                } else {
                    require(queryParameter("path").length in 1..8192)
                    replyDownload(sessions.workspace(id).download(queryParameter("path")))
                }
            }
            requestMethod == "GET" && path.startsWith("/api/artifacts/") -> {
                val file = sessions.artifact(id, path.removePrefix("/api/artifacts/"))
                if (file == null) reply(404, "application/json", """{"error":"artifact_not_found"}""")
                else replyDownload(file)
            }
            else -> reply(405, "application/json", """{"error":"method_not_allowed"}""")
        }
    } catch (failure: WorkspaceFailure) {
        reply(if (failure.code == "file_not_found") 404 else if (failure.code == "edit_conflict") 409 else 400, "application/json",
            JSONObject().put("error", failure.code).put("message", failure.message).toString())
    } catch (_: java.nio.file.NoSuchFileException) {
        reply(404, "application/json", """{"error":"file_not_found"}""")
    } catch (_: IllegalArgumentException) {
        reply(400, "application/json", """{"error":"invalid_request"}""")
    }
}

private fun HttpExchange.queryParameter(name: String): String = requestURI.rawQuery.orEmpty().split('&')
    .firstOrNull { it.substringBefore('=') == name }?.substringAfter('=')?.let { URLDecoder.decode(it, Charsets.UTF_8) }.orEmpty()

private fun HttpExchange.replyDownload(file: Pair<String, ByteArray>) {
    val filename = URLEncoder.encode(file.first, Charsets.UTF_8).replace("+", "%20")
    responseHeaders.set("Content-Disposition", "attachment; filename*=UTF-8''$filename")
    replyBytes(200, "application/octet-stream", file.second)
}
