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
    when {
        requestURI.path == "/api/settings" && requestMethod == "PUT" -> handleSaveRules(chat.sessions, session)
        requestMethod != "GET" -> reply(405, "application/json", """{"error":"method_not_allowed"}""")
        requestURI.path == "/api/settings/mcp" ->
            reply(200, "application/json", runBlocking { chat.mcpSettings() }.toString())
        !chat.sessions.exists(session) -> reply(404, "application/json", """{"error":"session_not_found"}""")
        else -> reply(200, "application/json", chat.integrationSettings(session).toString())
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
