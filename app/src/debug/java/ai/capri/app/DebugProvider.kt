package ai.capri.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** ADB shell / app UID only; compiled exclusively into the debug APK. No network listener. */
internal object DebugBridge {
    private var model = WeakReference<CapriModel>(null)
    private data class Request(val chat: String, val start: Int, val text: String, var end: Int? = null, var job: kotlinx.coroutines.Job? = null)
    private val requests = linkedMapOf<String, Request>()
    fun attach(model: CapriModel) { this.model = WeakReference(model) }
    fun call(method: String, args: Bundle): JSONObject {
        check(Looper.myLooper() == Looper.getMainLooper())
        val vm = model.get() ?: return JSONObject().put("error", "not_ready: open Capri first")
        return when (method) {
            "sessions" -> JSONObject().put("sessions", JSONArray().apply {
                vm.sessions.forEach { put(JSONObject().put("chat_id", it.id).put("title", it.title).put("busy", vm.jobs.containsKey(it.id))) }
            })
            "metrics" -> {
                val chat = args.getString("chat_id").orEmpty()
                require(vm.sessions.any { it.id == chat }) { "unknown chat_id" }
                val directory = java.io.File(vm.store.workspace.root(chat), "metrics")
                val file = directory.listFiles()?.filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.name.matches(Regex("[0-9a-f-]{36}\\.jsonl")) }?.maxByOrNull { it.lastModified() }
                val events = JSONArray()
                file?.takeIf { it.length() <= 262144 + 4096 }?.bufferedReader()?.useLines { lines ->
                    lines.take(2000).forEach { line -> runCatching { JSONObject(line) }.getOrNull()?.let { events.put(it) } }
                }
                JSONObject().put("trace_id", file?.nameWithoutExtension ?: JSONObject.NULL).put("events", events)
            }
            "send" -> {
                val id = args.getString("request_id").orEmpty()
                require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "request_id must be a UUID" }
                val text = args.getString("text").orEmpty()
                require(text.isNotBlank() && text.toByteArray().size <= 32000) { "text must be 1–32000 bytes" }
                val previous = requests[id]
                if (previous != null) {
                    require(previous.text == text && (args.getString("chat_id").isNullOrEmpty() || args.getString("chat_id") == previous.chat)) { "request_id conflict" }
                    snapshot(vm, id, previous, 0)
                } else {
                    if (requests.size >= 100) {
                        val old = requests.entries.firstOrNull { !vm.jobs.containsKey(it.value.chat) }
                        check(old != null) { "debug request limit" }; requests.remove(old.key)
                    }
                    check(!vm.migrating) { "environment preparation in progress; no message submitted" }
                    val chat = args.getString("chat_id")?.takeIf { it.isNotBlank() }
                    val session = if (chat == null) Session(title = "调试 · " + text.take(16)).also { vm.sessions.add(0, it); vm.prepareSession(it.id) }
                        else vm.sessions.firstOrNull { it.id == chat } ?: error("unknown chat_id")
                    check(!vm.jobs.containsKey(session.id)) { "chat busy" }
                    val request = Request(session.id, session.messages.size, text)
                    if (!vm.sendTo(session.id, text)) {
                        vm.persist()
                        JSONObject().put("error", vm.notices[session.id] ?: "message rejected").put("chat_id", session.id)
                    } else {
                        requests[id] = request
                        request.job = vm.jobs[session.id]
                        if (request.job == null) request.end = session.messages.size
                        else request.job!!.invokeOnCompletion { request.end = session.messages.size }
                        snapshot(vm, id, request, 0)
                    }
                }
            }
            "status", "cancel" -> {
                val id = args.getString("request_id").orEmpty()
                val request = requests[id] ?: return JSONObject().put("error", "unknown request_id: expired or app process restarted; do not resend automatically")
                if (method == "cancel") request.job?.cancel()
                snapshot(vm, id, request, args.getInt("offset", 0))
            }
            else -> JSONObject().put("error", "unknown method")
        }
    }
    private fun snapshot(vm: CapriModel, id: String, request: Request, offset: Int): JSONObject {
        val entries = vm.sessions.firstOrNull { it.id == request.chat }?.messages?.let { it.subList(request.start, request.end ?: it.size).toList() }.orEmpty()
        require(offset in 0..entries.size) { "invalid offset" }
        val page = entries.drop(offset).take(20)
        return JSONObject().put("request_id", id).put("chat_id", request.chat)
            .put("running", request.job?.isCompleted == false).put("notice", vm.notices[request.chat].orEmpty())
            .put("next_offset", offset + page.size).put("has_more", offset + page.size < entries.size)
            .put("entries", JSONArray().apply {
                page.forEach { put(JSONObject().put("role", it.role).put("text", redact(it.text).take(12000))) }
            })
    }
    private fun redact(text: String): String = text
        .replace(Regex("-----BEGIN [^-]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [^-]*PRIVATE KEY-----|$)"), "[PRIVATE KEY REDACTED]")
        .replace(Regex("\\b(?:sk-|github_pat_|ghp_)[A-Za-z0-9_-]+"), "[TOKEN REDACTED]")
}

class DebugProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && uid != 2000 && uid != 0) throw SecurityException("ADB shell only")
        val task = FutureTask { try {
            val decoded = extras?.getString("payload")?.let { encoded ->
                val json = DebugPayload.decode(encoded)
                Bundle().apply {
                    for (key in listOf("text", "request_id", "chat_id")) if (json.has(key)) putString(key, json.getString(key))
                    if (json.has("offset")) putInt("offset", json.getInt("offset"))
                }
            } ?: extras ?: Bundle()
            DebugBridge.call(method, decoded)
        }
            catch (error: Exception) { JSONObject().put("error", error.message ?: "debug request failed") } }
        val result = try {
            if (Looper.myLooper() == Looper.getMainLooper()) task.run() else Handler(Looper.getMainLooper()).post(task)
            task.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            task.cancel(false)
            JSONObject().put("error", "debug dispatch timed out; query request_id before retrying")
        }
        return Bundle().apply { putString("json", result.toString()) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** One encoding layer: Android's content CLI never sees the message's quoting. */
internal object DebugPayload {
    fun decode(encoded: String): JSONObject {
        require(encoded.length <= 64000) { "payload too large" }
        return JSONObject(String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8))
    }
}
