package ai.capri.host

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class LocalChat private constructor(
    endpoint: String,
    private val key: String,
    private val model: String,
    val sessions: HostSessions,
) {
    private val sending = Mutex()
    private val url = endpoint.toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
        require(it.isHttps || it.host in setOf("127.0.0.1", "::1", "localhost")) {
            "Model endpoint must use HTTPS or loopback."
        }
    }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(45, TimeUnit.SECONDS).build()

    suspend fun send(session: String, text: String): JSONObject {
        if (!sending.tryLock()) throw ChatBusy()
        try {
            val transcript = sessions.history(session)
            require(transcript.length() < 100) { "Session message limit reached." }
            transcript.put(message("user", text))
            val result = runTurn(text, JSONArray(transcript.toString()), ::infer)
            if (result.getString("outcome") == "complete") {
                // Persist only the visible exchange after delivery has been verified.
                transcript.put(message("assistant", result.getString("reply")))
                sessions.save(session, transcript)
            }
            return result
        } finally {
            sending.unlock()
        }
    }

    private fun infer(messages: JSONArray): JSONObject {
        val body = JSONObject().put("model", model).put("messages", messages)
            .put("stream", false).put("max_tokens", 512)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Model request failed." }
            val bytes = checkNotNull(response.body).byteStream().readNBytes(256 * 1024 + 1)
            check(bytes.size <= 256 * 1024) { "Model response is too large." }
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }

    companion object {
        fun fromEnvironment(): LocalChat? {
            val endpoint = System.getenv("CAPRI_MODEL_ENDPOINT") ?: return null
            val key = requireNotNull(System.getenv("CAPRI_MODEL_API_KEY")).also { require(it.isNotBlank()) }
            val model = requireNotNull(System.getenv("CAPRI_MODEL_NAME")).also { require(it.isNotBlank()) }
            val legacy = Path.of(System.getenv("CAPRI_CHAT_STATE") ?: "work/capri-host/chat.json").toAbsolutePath()
            val data = System.getenv("CAPRI_DATA_DIR")?.let(Path::of)
                ?: legacy.parent.resolve("data")
            return LocalChat(endpoint, key, model, HostSessions(data.toAbsolutePath(), legacy))
        }

        private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)
    }
}

private class ChatBusy : IllegalStateException("A remote turn is already running.")

internal fun HttpExchange.handleChat(chat: LocalChat) {
    val session = sessionId()
    if (!chat.sessions.exists(session)) {
        reply(404, "application/json", """{"error":"session_not_found"}""")
        return
    }
    val (status, payload) = when (requestMethod) {
        "GET" -> runCatching { 200 to JSONObject().put("messages", chat.sessions.history(session)) }
            .getOrElse { 500 to JSONObject().put("error", "history_unavailable") }
        "POST" -> {
            val bytes = requestBody.readNBytes(16_385)
            val text = if (bytes.size <= 16_384) {
                runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).getString("text").trim() }.getOrNull()
            } else null
            if (text.isNullOrEmpty() || text.length > 4_000) 400 to JSONObject().put("error", "invalid_message")
            else runCatching { 200 to runBlocking { chat.send(session, text) } }
                .getOrElse {
                    if (it is ChatBusy) 409 to JSONObject().put("error", "chat_busy")
                    else 502 to JSONObject().put("error", "turn_failed")
                }
        }
        else -> 405 to JSONObject().put("error", "method_not_allowed")
    }
    reply(status, "application/json", payload.toString())
}

private fun HttpExchange.sessionId(): String =
    requestURI.rawQuery?.removePrefix("session=") ?: HostSessions.DEFAULT

internal fun HttpExchange.handleSessions(sessions: HostSessions) {
    if (requestURI.path == "/api/sessions/export" && requestMethod == "POST") {
        val session = sessionId()
        if (!sessions.exists(session)) {
            reply(404, "application/json", """{"error":"session_not_found"}""")
            return
        }
        val artifact = sessions.export(session)
        responseHeaders.set("Content-Disposition", "attachment; filename=capri-conversation.md")
        reply(200, "text/markdown", artifact)
        return
    }
    val result = when {
        requestURI.path == "/api/sessions" && requestMethod == "GET" ->
            200 to JSONObject().put("sessions", sessions.list())
        requestURI.path == "/api/sessions" && requestMethod == "POST" -> {
            val bytes = requestBody.readNBytes(1025)
            val title = if (bytes.size <= 1024) runCatching {
                JSONObject(bytes.toString(Charsets.UTF_8)).getString("title").trim()
            }.getOrNull() else null
            if (title.isNullOrBlank() || title.length > 80) 400 to JSONObject().put("error", "invalid_title")
            else 201 to sessions.create(title)
        }
        else -> 405 to JSONObject().put("error", "method_not_allowed")
    }
    reply(result.first, "application/json", result.second.toString())
}
