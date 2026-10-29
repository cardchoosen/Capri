package ai.capri.app

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** On-demand, debug-only Unix socket. ADB forwards it; no phone TCP/LAN listener. */
internal object DebugSocketBridge {
    private val name = "ai.capri.app.web.${Process.myUid()}"
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private var listener: LocalServerSocket? = null
    private val main = Handler(Looper.getMainLooper())
    private val workers = ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "CapriWebConnection").apply { isDaemon = true } })
    private val methods = setOf("web_state", "web_revise", "web_message", "web_create", "web_stop", "web_delete", "web_select", "web_attachment", "web_catalog", "web_runtime", "send", "status")

    @Synchronized fun connectionInfo(): JSONObject {
        if (listener == null) {
            val server = LocalServerSocket(name)
            listener = server
            Thread({
                while (true) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    try {
                        val uid = socket.peerCredentials.uid
                        if (uid !in setOf(0, 2000, Process.myUid())) socket.close()
                        else workers.execute { serve(socket) }
                    } catch (_: Exception) { runCatching { socket.close() } }
                }
            }, "CapriWebAccept").apply { isDaemon = true; start() }
        }
        return JSONObject().put("socket", name).put("token", token).put("epoch", DebugWebBridge.epoch)
    }

    private fun line(reader: BufferedReader): String? {
        val result = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next < 0) return null
            if (next == 10) return result.toString()
            require(result.length < 64000) { "request too large" }
            result.append(next.toChar())
        }
    }

    private fun serve(socket: LocalSocket) {
        socket.use {
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val output = socket.outputStream.bufferedWriter(Charsets.UTF_8)
            while (true) {
                val raw = try { line(reader) } catch (_: Exception) { break } ?: break
                val result = try {
                    val request = JSONObject(raw)
                    require(java.security.MessageDigest.isEqual(request.getString("token").toByteArray(), token.toByteArray())) { "unauthorized" }
                    val method = request.getString("method")
                    require(method in methods) { "unknown method" }
                    val values = request.optJSONObject("args") ?: JSONObject()
                    val task = FutureTask {
                        try {
                            val bundle = Bundle().apply {
                                for (key in listOf("text", "request_id", "chat_id", "epoch", "category", "query", "id", "skill_type", "action", "entry_id", "from_context", "context_id", "mode")) if (values.has(key)) putString(key, values.getString(key))
                                for (key in listOf("offset", "before", "revision", "entry")) if (values.has(key)) putInt(key, values.getInt(key))
                            }
                            if (values.has("history_revision")) bundle.putLong("history_revision", values.getLong("history_revision"))
                            if (values.has("payload")) bundle.putString("payload", values.getJSONObject("payload").toString())
                            when (method) {
                                "web_attachment" -> DebugBridge.attachment(bundle)
                                "web_catalog" -> DebugBridge.catalog(bundle)
                                "web_runtime" -> DebugBridge.runtime(bundle)
                                else -> DebugBridge.call(method, bundle)
                            }
                        } catch (error: Exception) { JSONObject().put("error", error.message ?: "request failed") }
                    }
                    main.post(task)
                    val dispatched = try { task.get(5, TimeUnit.SECONDS) }
                    catch (_: Exception) {
                        task.cancel(false)
                        JSONObject().put("error", "dispatch timed out; inspect history before retrying")
                    }
                    when (dispatched) {
                        is DebugWebAttachments.Ticket -> dispatched.read()
                        is DebugWebCatalog.Ticket -> dispatched.read()
                        is DebugWebRuntime.Ticket -> dispatched.read()
                        is JSONObject -> dispatched
                        else -> JSONObject().put("error", "invalid response")
                    }
                } catch (_: Exception) { JSONObject().put("error", "invalid web request") }
                try { output.write(result.toString()); output.newLine(); output.flush() } catch (_: Exception) { break }
            }
        }
    }
}
