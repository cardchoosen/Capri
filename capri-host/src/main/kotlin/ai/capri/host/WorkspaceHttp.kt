package ai.capri.host

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

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
            requestMethod == "GET" && path == "/api/workspace/file" -> {
                val encoded = requestURI.rawQuery?.substringAfter("&path=", "") ?: ""
                require(encoded.length in 1..8192)
                replyDownload(sessions.workspace(id).download(URLDecoder.decode(encoded, Charsets.UTF_8)))
            }
            requestMethod == "GET" && path.startsWith("/api/artifacts/") -> {
                val file = sessions.artifact(id, path.removePrefix("/api/artifacts/"))
                if (file == null) reply(404, "application/json", """{"error":"artifact_not_found"}""")
                else replyDownload(file)
            }
            else -> reply(405, "application/json", """{"error":"method_not_allowed"}""")
        }
    } catch (failure: WorkspaceFailure) {
        reply(if (failure.code == "file_not_found") 404 else 400, "application/json",
            JSONObject().put("error", failure.code).put("message", failure.message).toString())
    } catch (_: java.nio.file.NoSuchFileException) {
        reply(404, "application/json", """{"error":"file_not_found"}""")
    } catch (_: IllegalArgumentException) {
        reply(400, "application/json", """{"error":"invalid_request"}""")
    }
}

private fun HttpExchange.replyDownload(file: Pair<String, ByteArray>) {
    val filename = URLEncoder.encode(file.first, Charsets.UTF_8).replace("+", "%20")
    responseHeaders.set("Content-Disposition", "attachment; filename*=UTF-8''$filename")
    replyBytes(200, "application/octet-stream", file.second)
}
