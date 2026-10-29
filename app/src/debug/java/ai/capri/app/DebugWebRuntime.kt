package ai.capri.app

import android.os.Bundle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import ai.capri.app.model.*

/** Debug USB management. Secrets are write-only; operations are deduplicated and owned by the model lifecycle. */
internal object DebugWebRuntime {
    private data class Operation(val fingerprint: String, val chat: String, var value: JSONObject, var job: Job? = null)
    private val operations = linkedMapOf<String, Operation>()
    private data class Preview(val chat: String, val file: File, val size: Long, val modified: Long)
    private val previews = linkedMapOf<String, Preview>()
    internal fun rememberPreview(vm: CapriModel, chat: String, data: JSONObject): JSONObject {
        val path = data.getString("path")
        require(path.matches(Regex("browser/[0-9a-f-]{36}\\.png")))
        val root = vm.store.workspace.folder(chat, "workspace").absoluteFile
        val file = File(root, path)
        for (part in listOf(root.parentFile!!.parentFile!!, root.parentFile!!, root, file.parentFile!!, file)) require(!java.nio.file.Files.isSymbolicLink(part.toPath()))
        require(file.isFile && file.length() in 8..8388608)
        val id = UUID.randomUUID().toString(); while (previews.size >= 16) previews.remove(previews.keys.first())
        previews[id] = Preview(chat, file, file.length(), file.lastModified()); return data.put("preview_id", id)
    }
    private val mutation = Mutex()
    private data class Upload(val chat: String, val kind: String, val file: File, val size: Int, val created: Long = android.os.SystemClock.elapsedRealtime())
    private val uploads = java.util.concurrent.ConcurrentHashMap<String, Upload>()
    private suspend fun upload(chat: String, id: String, kind: String, block: suspend (File) -> JSONObject): JSONObject {
        val u = uploads[id] ?: error("Upload unavailable.")
        require(u.chat == chat && u.kind == kind && u.file.length() == u.size.toLong())
        try { return block(u.file) } finally { u.file.delete(); uploads.remove(id) }
    }
    private fun obj(value: String) = runCatching { JSONObject(value) }.getOrElse { JSONObject().put("message", value.take(24000)) }
    private fun result(value: Any = "Completed") = JSONObject().put("result", value)
    private fun confirmed(p: JSONObject) { require(p.optBoolean("confirm")) { "Confirmation required." } }
    private fun idle(vm: CapriModel) { check(vm.jobs.isEmpty() && !vm.runtimeBusy && !vm.migrating && !vm.resettingMemory && !vm.memoryResetRequired && vm.deletingChats.isEmpty()) { "Phone is busy; wait before changing shared state." } }
    fun prepare(vm: CapriModel, args: Bundle): Ticket {
        require(args.getString("epoch") == DebugWebBridge.epoch) { "App restarted; refresh Run." }
        val chat = args.getString("chat_id").orEmpty()
        require(vm.sessions.any { it.id == chat } && chat !in vm.deletingChats) { "Unknown or deleting chat." }
        return Ticket(vm, chat, args.getString("action").orEmpty(), JSONObject(args.getString("payload") ?: "{}"), args.getString("request_id").orEmpty())
    }
    class Ticket internal constructor(private val vm: CapriModel, private val chat: String, private val action: String, private val p: JSONObject, private val id: String) {
        fun read(): JSONObject = runBlocking {
            withContext(Dispatchers.Main) {
                check(DebugBridge.currentModel() === vm && vm.sessions.any { it.id == chat } && chat !in vm.deletingChats) { "Session or model is no longer available." }
                if (action == "preview_chunk") {
                    val preview = previews[p.getString("id")] ?: error("Screenshot preview expired.")
                    require(preview.chat == chat)
                    val offset = p.optLong("offset", 0)
                    return@withContext withContext(Dispatchers.IO) {
                        val file = preview.file
                        for (part in listOf(file.parentFile!!.parentFile!!.parentFile!!.parentFile!!, file.parentFile!!.parentFile!!.parentFile!!, file.parentFile!!.parentFile!!, file.parentFile!!, file)) require(!java.nio.file.Files.isSymbolicLink(part.toPath()))
                        require(file.length() == preview.size && file.lastModified() == preview.modified && offset in 0..preview.size)
                        java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
                            channel.position(offset); val buffer = java.nio.ByteBuffer.allocate(minOf(24000L, preview.size-offset).toInt())
                            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { ensureActive() }
                            require(channel.size() == preview.size && file.lastModified() == preview.modified)
                            JSONObject().put("base64", android.util.Base64.encodeToString(buffer.array().copyOf(buffer.position()), android.util.Base64.NO_WRAP)).put("size", preview.size).put("next", offset+buffer.position())
                        }
                    }
                }
                if (action == "upload_abort") {
                    val u = uploads[p.getString("id")] ?: return@withContext result("Upload already removed.")
                    require(u.chat == chat)
                    withContext(Dispatchers.IO) { synchronized(u) { u.file.delete(); uploads.remove(p.getString("id")) } }
                    return@withContext result("Upload removed.")
                }
                if (action == "upload_chunk") {
                    val u = uploads[p.getString("id")] ?: error("Upload unavailable.")
                    require(u.chat == chat && android.os.SystemClock.elapsedRealtime() - u.created < 1200000)
                    val bytes = android.util.Base64.decode(p.getString("base64"), android.util.Base64.DEFAULT)
                    require(bytes.size in 1..24000)
                    val offset = p.getLong("offset")
                    return@withContext withContext(Dispatchers.IO) {
                        synchronized(u) {
                            require(offset == u.file.length() && offset + bytes.size <= u.size)
                            u.file.appendBytes(bytes); result(u.file.length())
                        }
                    }
                }
                if (action == "operation") {
                    val op = operations[p.getString("id")] ?: error("Unknown operation; inspect phone state before repeating.")
                    require(op.chat == chat) { "Operation belongs to another chat." }
                    return@withContext JSONObject(op.value.toString())
                }
                if (action == "cancel_operation") {
                    val op = operations[p.getString("id")] ?: error("Unknown operation.")
                    require(op.chat == chat); op.job?.cancel(); return@withContext result("Cancellation requested; completed effects remain.")
                }
                if (action == "snapshot") return@withContext snapshot(vm, chat, p.optString("section", "overview"), p)
                require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "request_id must be a UUID." }
                val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest((chat + action + p.toString()).toByteArray()).joinToString("") { "%02x".format(it) }
                operations[id]?.let { require(it.fingerprint == fingerprint) { "request_id conflict." }; return@withContext JSONObject(it.value.toString()) }
                check(operations.values.none { it.job?.isActive == true }) { "Another Run operation is active; inspect its result first." }
                while (operations.size >= 64) operations.remove(operations.keys.first())
                val op = Operation(fingerprint, chat, JSONObject().put("operation_id", id).put("state", "running")); operations[id] = op
                op.job = vm.viewModelScope.launch {
                    try {
                        val data = mutation.withLock {
                            check(vm.sessions.any { it.id == chat } && chat !in vm.deletingChats) { "Session unavailable." }
                            execute(vm, chat, action, p)
                        }
                        val encoded = data.toString()
                        op.value = JSONObject().put("operation_id", id).put("state", "completed").put("data", if (encoded.length <= 48000) data else JSONObject().put("message", "Result exceeded the display limit; narrow the request.").put("truncated", true))
                    } catch (e: CancellationException) {
                        op.value = JSONObject().put("operation_id", id).put("state", "cancelled").put("message", "Interrupted; inspect current state before repeating. Some effects may have completed.")
                        throw e
                    } catch (_: Exception) {
                        // Exception text may contain a credential-bearing URL or command. Never echo it.
                        op.value = JSONObject().put("operation_id", id).put("state", "failed").put("message", "Operation failed. Check inputs, phone authorization and current state before retrying.")
                    } finally { vm.refresh() }
                }
                JSONObject().put("operation_id", id).put("state", "running")
            }
        }.let { value -> if (value.toString().length <= 100000) value else JSONObject().put("error", "Run response exceeded its display budget; narrow the request.") }
    }
    private suspend fun snapshot(vm: CapriModel, chat: String, section: String, p: JSONObject): JSONObject {
        return when (section) {
            "overview" -> {
                val state = vm.runtime.environment.chats.value[chat] ?: vm.runtime.environment.state.value
                JSONObject().put("chat_id", chat).put("title", vm.sessions.first { it.id == chat }.title).put("runtime_ready", vm.runtime.ready)
                    .put("phase", state.phase).put("message", state.message).put("busy", vm.runtimeBusy).put("runtime_result", vm.runtimeResult.take(24000))
                    .put("root", vm.androidTools.rootStatus()).put("memory_reset", vm.memoryResetStatus).put("browser", vm.browser.status())
                    .put("maintenance", JSONArray(EnvironmentMaintenance.Action.entries.map { JSONObject().put("id", it.name).put("label", it.label).put("scope", it.scope) }))
            }
            "models" -> JSONObject().put("providers", JSONArray(Providers.all.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("protocol", it.protocol.name) }))
                .put("accounts", JSONArray(vm.store.models.all().map { JSONObject().put("id", it.id).put("provider", it.provider).put("name", it.name).put("endpoint", it.endpoint).put("protocol", it.protocol.name).put("count", it.models.size).put("has_key", it.key.isNotBlank()) }))
                .put("selected", vm.store.models.selected(chat)?.let { JSONObject().put("account", it.first.id).put("model", it.second.id) } ?: JSONObject.NULL)
            "model_options" -> {
                val a = vm.store.models.all().first { it.id == p.getString("id") }; val offset = p.optInt("offset", 0); require(offset >= 0)
                val items = a.models.drop(offset).take(50)
                JSONObject().put("models", JSONArray(items.map { it.json() })).put("next", offset + items.size).put("has_more", offset + items.size < a.models.size)
            }
            "mcp_config" -> withContext(Dispatchers.IO) { JSONObject().put("config", vm.extensions.store.config(p.getString("id"))) }
            "native" -> vm.androidTools.capabilities()
            "terminal" -> withContext(Dispatchers.IO) { JSONObject().put("terminals", JSONArray(vm.terminals.list(chat))) }
            "git" -> withContext(Dispatchers.IO) { JSONObject().put("accounts", JSONArray(vm.gitAccounts.list().map { JSONObject().put("host", it.host).put("username", it.username) })) }
            "extensions" -> withContext(Dispatchers.IO) {
                JSONObject().put("mcp", JSONArray(vm.extensions.store.configs().map { c ->
                    // No raw command/env/URL or stored credential is exported by routine snapshots.
                    JSONObject().put("id", c.getString("id")).put("name", c.optString("name")).put("type", c.getString("type"))
                        .put("enabled", c.optBoolean("enabled")).put("state", vm.extensions.state(chat, c.getString("id"))).put("has_secret", vm.extensions.hasCredential(c))
                })).put("skills", JSONArray(vm.extensions.store.skills().map { s -> JSONObject().put("id", s.id).put("name", s.name).put("builtin", s.builtin)
                    .put("enabled", vm.extensions.store.enabled(s)).put("category", vm.extensions.store.category(s).id) }))
            }
            "knowledge" -> {
                val personal = vm.sessions.first { it.id == chat }.kind == SessionKind.PERSONAL
                val conversation = HistoryEditing.actualContextKey(vm.sessions.first { it.id == chat })
                withContext(Dispatchers.IO) {
                    val records = vm.knowledge.records(chat); val offset = p.optInt("offset", 0); require(offset >= 0)
                    val page = records.drop(offset).take(8).map { r -> JSONObject(r.toString()).apply { remove("chunks"); if (has("text")) put("text", getString("text").take(2000)); put("text_truncated", r.optString("text").length > 2000) } }
                    JSONObject().put("personal", personal).put("records", JSONArray(page)).put("next", offset + page.size).put("has_more", offset + page.size < records.size)
                        .put("summary", vm.contextHistory.summary(conversation).take(16000)).put("budget", vm.contextHistory.budget(conversation))
                }
            }
            "enhancements" -> JSONObject().put("deepseek_web", vm.enhancements.enabled(ai.capri.app.retrieval.ProviderCapabilities.DEEPSEEK_WEB))
                .put("supported", ai.capri.app.retrieval.ProviderCapabilities.deepSeek(vm.store.config(chat))).put("status", vm.enhancements.deepSeekStatus)
            "archive" -> withContext(Dispatchers.IO) { obj(vm.installationEnvironment.document(p)) }
            "browser" -> obj(vm.browser.execute(chat, JSONObject().put("action", "tabs")))
            else -> error("Unknown Run section.")
        }
    }
    private suspend fun execute(vm: CapriModel, chat: String, action: String, p: JSONObject): JSONObject {
        val context = vm.getApplication<android.app.Application>()
        return when (action) {
            "upload_begin" -> withContext(Dispatchers.IO) {
                uploads.entries.removeAll { (_, u) -> if (android.os.SystemClock.elapsedRealtime() - u.created > 1200000) { u.file.delete(); true } else false }
                require(uploads.size < 2); val kind = p.getString("kind"); val size = p.getInt("size")
                require(kind in setOf("ssh", "skill") && size in 1..if (kind == "ssh") 65536 else 10000000)
                val directory = File(context.cacheDir, "web-imports").apply { mkdirs() }
                if (uploads.isEmpty()) directory.listFiles().orEmpty().forEach { it.delete() }
                val id = UUID.randomUUID().toString(); val file = File(directory, id); check(file.createNewFile()); android.system.Os.chmod(file.path, 384)
                uploads[id] = Upload(chat, kind, file, size); result(id)
            }
            "model_select" -> { check(!vm.jobs.containsKey(chat)); vm.store.models.select(chat, p.getString("account"), p.getString("model")); result() }
            "model_save", "model_discover" -> {
                idle(vm)
                val old = vm.store.models.all().firstOrNull { it.id == p.optString("id") }
                val provider = Providers.get(p.getString("provider")); val endpoint = Providers.endpoint(p.getString("endpoint"))
                val key = p.optString("key").trim().ifBlank { old?.takeIf { it.provider == provider.id && it.endpoint == endpoint }?.key.orEmpty() }
                require(key.isNotBlank() && key.length <= 8192 && key.none { it == '\n' || it == '\r' }) { "Invalid key." }
                val protocol = ModelProtocol.valueOf(p.getString("protocol"))
                val models = (if (old?.endpoint == endpoint && old.protocol == protocol && old.key == key) old.models else emptyList()).toMutableList()
                p.optString("manual").trim().takeIf { it.isNotBlank() && models.none { m -> m.id == it } }?.let { models += ModelOption(it, manual = true) }
                val catalog = ModelCatalog(withContext(Dispatchers.IO) { context.assets.open("model_catalog.json").bufferedReader().use { it.readText() } })
                var account = ModelAccount(id = old?.id ?: UUID.randomUUID().toString(), provider = provider.id, name = p.getString("name"), endpoint = endpoint, key = key, protocol = protocol, models = models)
                account = account.copy(models = catalog.enrich(account, models))
                if (action == "model_discover") {
                    val found = ModelApi(catalog = catalog).models(account); require(found.isNotEmpty())
                    account = account.copy(models = (found + models.filter { it.manual && found.none { m -> m.id == it.id } }).distinctBy { it.id })
                }
                idle(vm); vm.store.models.save(account); result("Saved API configuration and ${account.models.size} models.")
            }
            "model_remove" -> { idle(vm); confirmed(p); vm.store.models.remove(p.getString("id")); result() }
            "model_balance" -> result(ModelApi().balance(vm.store.models.all().first { it.id == p.getString("id") }))
            "terminal" -> { check(!vm.migrating && !vm.runtimeBusy); obj(vm.terminals.execute(chat, p.getJSONObject("args"))) }
            "browser" -> {
                val args = p.getJSONObject("args"); val data = obj(vm.browser.execute(chat, args))
                if (args.optString("action") == "screenshot" && data.optString("status") == "ok" && data.has("path")) rememberPreview(vm, chat, data) else data
            }
            "browser_home" -> { vm.select(chat); vm.previews.openBrowserHome(chat); vm.activeTab = 0; result("Browser preview opened on phone.") }
            "battery_settings" -> { ai.capri.app.terminal.TerminalBackgroundPolicy.openSettings(context); result("Complete system settings on the phone, then return to Capri.") }
            "root_request" -> { idle(vm); confirmed(p); obj(vm.androidTools.execute(JSONObject().put("action", "root_request"))) }
            "enhancement" -> { val value = p.getBoolean("enabled"); require(!value || ai.capri.app.retrieval.ProviderCapabilities.deepSeek(vm.store.config(chat))); if (value) confirmed(p)
                vm.enhancements.enable(ai.capri.app.retrieval.ProviderCapabilities.DEEPSEEK_WEB, value); result() }
            "git_save" -> { idle(vm); withContext(Dispatchers.IO) { val a = vm.gitAccounts.saveFromSettings(p.getString("host"), p.optString("username"), p.optString("token")); result(a.host + " · " + a.username) } }
            "git_remove" -> { idle(vm); confirmed(p); withContext(Dispatchers.IO) { vm.gitAccounts.remove(p.getString("host")); result() } }
            "git_test" -> withContext(Dispatchers.IO) { result(ai.capri.app.git.GitAccountTest.check(vm.gitAccounts, p.getString("host"), p.getString("repository"))) }
            "ssh_inspect", "ssh_generate" -> withContext(Dispatchers.IO) { if (action == "ssh_generate") confirmed(p)
                val output = vm.runtime.execute(chat, SshEnvironment.inspection(action == "ssh_generate")); check(output.startsWith("exit=0\n"))
                JSONObject().put("device", JSONObject(output.substringAfter('\n'))).put("identities", sshIdentities(vm, chat)) }
            "ssh_import" -> { idle(vm); confirmed(p); withContext(Dispatchers.IO) {
                val name = p.getString("name"); require(name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,39}")))
                upload(chat, p.getString("upload_id"), "ssh") { source ->
                    val file = File(SshEnvironment.prepare(context), "import-${UUID.randomUUID()}")
                    try { check(file.createNewFile()); android.system.Os.chmod(file.path, 384); source.inputStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                        val out = SshEnvironment.identities(vm.runtime, chat, name, file.name); check(out.startsWith("exit=0\n")); obj(out.substringAfter('\n'))
                    } finally { file.delete() }
                }
            } }
            "mcp_save" -> { idle(vm); withContext(Dispatchers.IO) {
                val raw = JSONObject(p.getJSONObject("config").toString()); if (p.optBoolean("replace_secret")) raw.put("credential", "stored")
                val config = ai.capri.app.extensions.ExtensionPolicy.config(raw); val id = config.getString("id")
                val exists = vm.extensions.store.configs().any { it.getString("id") == id }
                require(p.optBoolean("editing") == exists) { "MCP create/update conflict." }
                if (exists) require(id == p.getString("original_id")) { "Existing MCP ID cannot change." }
                vm.extensions.disconnectConfig(id); vm.extensions.store.saveConfig(config)
                if (p.optBoolean("replace_secret")) vm.extensions.store.saveSecret(id, p.optString("token")); result()
            } }
            "mcp_toggle", "mcp_remove", "mcp_activate", "mcp_deactivate" -> { idle(vm); val id = p.getString("id")
                withContext(Dispatchers.IO) {
                    when (action) {
                        "mcp_remove" -> { confirmed(p); vm.extensions.disconnectConfig(id); vm.extensions.store.removeConfig(id) }
                        "mcp_toggle" -> { val c = vm.extensions.store.config(id); vm.extensions.disconnectConfig(id); vm.extensions.store.saveConfig(c.put("enabled", p.getBoolean("enabled"))) }
                        "mcp_activate" -> vm.extensions.activate(chat, id)
                        else -> vm.extensions.deactivate(chat, id)
                    }; result()
                }
            }
            "skill_toggle", "skill_category", "skill_remove", "skill_import" -> { idle(vm); withContext(Dispatchers.IO) {
                val store = vm.extensions.store
                when (action) {
                    "skill_toggle" -> store.enable(store.skills().first { it.id == p.getString("id") }, p.getBoolean("enabled"))
                    "skill_category" -> store.setCategory(store.skills().first { it.id == p.getString("id") }, ai.capri.app.extensions.SkillCategory.from(p.getString("category")))
                    "skill_remove" -> { confirmed(p); store.removeSkill(p.getString("id")) }
                    else -> { confirmed(p); return@withContext upload(chat, p.getString("upload_id"), "skill") { file -> result(file.inputStream().use { store.importSkill(it, p.optBoolean("zip")) }) } }
                }; result()
            } }
            "knowledge_search", "memory_save", "document_index", "record_change", "history_search" -> {
                check(!vm.resettingMemory && !vm.memoryResetRequired)
                val personal = vm.sessions.first { it.id == chat }.kind == SessionKind.PERSONAL
                val history = vm.sessions.first { it.id == chat }.messages.toList()
                val conversation = HistoryEditing.actualContextKey(vm.sessions.first { it.id == chat })
                require(!(personal && action == "memory_save")) { "Use the personal memory lifecycle." }
                withContext(Dispatchers.IO) { when (action) {
                    "knowledge_search" -> result(vm.knowledge.search(chat, p.getString("query")))
                    "history_search" -> obj(vm.contextHistory.query(conversation, history, JSONObject().put("query", p.getString("query"))))
                    "memory_save" -> { vm.knowledge.putMemory(chat, p.getString("text"), "User entered in Run web settings", p.optBoolean("global"), p.optString("id")); result() }
                    "document_index" -> vm.knowledge.index(chat, p.getString("path"), p.optBoolean("global"))
                    else -> { if (!p.has("enabled")) confirmed(p); vm.knowledge.change(chat, p.getString("id"), if (p.has("enabled")) p.getBoolean("enabled") else null); result() }
                } }
            }
            "memory_reset" -> { idle(vm); confirmed(p); vm.resetSharedMemory(); result("Reset started; inspect shared-memory status on Overview.") }
            "environment_prepare" -> { idle(vm); vm.prepareSession(chat); result("Preparation started; inspect Overview.") }
            "environment_inspect" -> { idle(vm); vm.runtimeBusy = true; try { val value = vm.capabilityTool.execute(chat, JSONObject().put("action", "inspect")); vm.runtimeResult = value; obj(value) } finally { vm.runtimeBusy = false } }
            "maintenance_estimate" -> result(withContext(Dispatchers.IO) { EnvironmentMaintenance(context, vm.store.workspace, vm.runtime).estimate(chat, EnvironmentMaintenance.Action.valueOf(p.getString("id"))) })
            "maintenance" -> { idle(vm); confirmed(p); vm.maintainEnvironment(chat, EnvironmentMaintenance.Action.valueOf(p.getString("id"))); result("Maintenance started; inspect Overview.") }
            else -> error("Unknown Run operation.")
        }
    }
    private suspend fun sshIdentities(vm: CapriModel, chat: String): JSONObject {
        val out = SshEnvironment.identities(vm.runtime, chat); check(out.startsWith("exit=0\n")); return JSONObject(out.substringAfter('\n'))
    }
}
