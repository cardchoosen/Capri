package ai.capri.app.terminal

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import ai.capri.app.PythonRuntime
import ai.capri.app.Workspace
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class TerminalProblem(val status: String, message: String) : Exception(message)

/** Application-scoped owner: Model/ViewModel cancellation never owns a persistent terminal. */
internal class TerminalManager private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: TerminalManager? = null
        fun get(context: Context): TerminalManager = instance ?: synchronized(this) {
            instance ?: TerminalManager(context.applicationContext).also { instance = it }
        }
        private val liveStates = setOf("starting", "ready", "running")
    }
    private val workspace = Workspace(File(context.filesDir, "sessions"))
    private val runtime = PythonRuntime(context, workspace)
    private val journal = AtomicFile(File(context.filesDir, "terminals.json"))
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Capri:terminal-command").apply { setReferenceCounted(false) }
    private val records = linkedMapOf<String, JSONObject>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val revision = MutableStateFlow(0)
    val updates = revision.asStateFlow()
    @Volatile private var service: TerminalService? = null
    @Volatile private var serviceStopping = false
    @Volatile var serviceFailure: String? = null

    init {
        // No PID reattachment: old PIDs may have been reused, and the shell state cannot be restored.
        val recovered = runCatching { JSONArray(journal.openRead().bufferedReader().use { it.readText() }) }.getOrDefault(JSONArray())
        for (index in 0 until recovered.length()) {
            val record = recovered.getJSONObject(index)
            val id = record.getString("terminal_id")
            val chat = record.getString("chat_id")
            if (!id.matches(Regex("[0-9a-f-]{36}")) || !chat.matches(Regex("[a-zA-Z0-9-]+"))) continue
            reconcile(record)
            if (record.getString("state") in liveStates) {
                record.put("state", "interrupted").put("reason", "应用进程已终止；请新建终端，工作区和历史保留")
                record.remove("shell_pid")
                val commands = record.getJSONArray("commands")
                for (j in 0 until commands.length()) {
                    val command = commands.getJSONObject(j)
                    if (command.getString("state") == "running") command.put("state", "interrupted").put("reason", "应用进程已终止")
                }
            }
            records[id] = record
        }
        if (records.isNotEmpty()) persist()
        scope.launch {
            while (isActive) {
                delay(if (processes.isEmpty()) 1000 else 100)
                mutex.withLock {
                    var changed = false
                    processes.entries.toList().forEach { (id, process) ->
                        val record = records[id] ?: return@forEach
                        changed = reconcile(record) || changed
                        val log = File(folder(record), "supervisor.log")
                        if (!process.isAlive || log.length() > 200_000 || service == null) {
                            processes.remove(id)
                            process.destroyForcibly()
                            val state = readJson(File(folder(record), "state.json"))
                            endRecord(record, if (service == null) "interrupted" else if (state?.optString("reason") == "shell_exited") "closed" else "error",
                                if (service == null) "前台终端服务已停止" else if (log.length() > 200_000) "supervisor_output_limit" else state?.optString("reason")?.ifBlank { null } ?: "终端进程异常结束")
                            changed = true
                        }
                    }
                    if (changed) persist()
                    updateWakeLock()
                    stopServiceIfIdle()
                }
            }
        }
    }

    fun attach(value: TerminalService) { service = value; serviceFailure = null }
    fun detach(value: TerminalService) {
        if (service === value) service = null
        serviceStopping = false
        val affected = processes.entries.toList()
        affected.forEach { (id, process) -> processes.remove(id, process); process.destroyForcibly() }
        if (wakeLock.isHeld) wakeLock.release()
        scope.launch { mutex.withLock {
            affected.forEach { (id, _) -> records[id]?.let { endRecord(it, "interrupted", "前台终端服务已停止") } }
            if (affected.isNotEmpty()) persist()
        } }
    }
    fun stopFromNotification() { scope.launch { mutex.withLock {
        processes.keys.toList().forEach { id -> records[id]?.let { close(it) } }
        persist(); stopServiceIfIdle()
    } } }

    private suspend fun ensureService() {
        if (withTimeoutOrNull(10_000) { while (serviceStopping) delay(20); true } != true)
            throw TerminalProblem("timeout", "上次终端服务尚未停止，请稍后重试")
        if (service != null) return
        serviceFailure = null
        try { ContextCompat.startForegroundService(context, Intent(context, TerminalService::class.java)) }
        catch (error: Exception) { throw TerminalProblem("foreground_required", "请返回Capri前台后启动终端：${error.message}") }
        val attached = withTimeoutOrNull(10_000) {
            while (service == null) {
                serviceFailure?.let { throw TerminalProblem("unavailable", it) }
                delay(20)
            }
            true
        }
        if (attached != true) throw TerminalProblem("timeout", "终端前台服务启动超时")
    }
    private fun stopServiceIfIdle() {
        if (processes.isEmpty() && !serviceStopping && service != null) {
            serviceStopping = true
            service?.stopSelf()
        }
    }
    private fun updateWakeLock(renew: Boolean = false) {
        val needed = service != null && records.values.any { it.getString("state") in liveStates && active(it) != null }
        if (needed && (!wakeLock.isHeld || renew)) wakeLock.acquire(86_430_000L)
        else if (!needed && wakeLock.isHeld) wakeLock.release()
    }
    private fun folder(record: JSONObject) = File(workspace.folder(record.getString("chat_id"), "terminals"), record.getString("terminal_id"))
    private fun commandFolder(record: JSONObject, id: String) = File(folder(record), "commands/$id")
    private fun readJson(file: File) = runCatching { JSONObject(file.readText()) }.getOrNull()
    private fun atomic(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(value.toString().toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    private fun persist() {
        val stream = journal.startWrite()
        try { stream.write(JSONArray(records.values.toList()).toString().toByteArray()); journal.finishWrite(stream) }
        catch (error: Exception) { journal.failWrite(stream); throw error }
        revision.value++
    }
    private fun owned(chat: String, id: String): JSONObject = records[id]?.takeIf { it.getString("chat_id") == chat }
        ?: throw TerminalProblem("not_found", "当前会话没有此终端")
    private fun command(record: JSONObject, id: String): JSONObject {
        val commands = record.getJSONArray("commands")
        return (0 until commands.length()).map { commands.getJSONObject(it) }.firstOrNull { it.getString("command_id") == id }
            ?: throw TerminalProblem("not_found", "此终端没有该命令")
    }
    private fun active(record: JSONObject): JSONObject? {
        val commands = record.getJSONArray("commands")
        return (0 until commands.length()).map { commands.getJSONObject(it) }.lastOrNull { it.getString("state") == "running" }
    }
    private fun requireLive(record: JSONObject) {
        if (processes[record.getString("terminal_id")]?.isAlive != true || record.getString("state") !in liveStates)
            throw TerminalProblem("unavailable", "终端已结束或中断，请新建终端；历史和工作区保留")
    }
    private fun endRecord(record: JSONObject, state: String, reason: String) {
        reconcile(record)
        record.put("state", state).put("reason", reason)
        record.remove("shell_pid")
        active(record)?.put("state", if (state == "closed") "cancelled" else "interrupted")?.put("reason", reason)
    }
    private fun reconcile(record: JSONObject): Boolean {
        val active = active(record) ?: return false
        val result = readJson(File(commandFolder(record, active.getString("command_id")), "result.json")) ?: return false
        val reason = result.optString("reason", "exited")
        val code = result.opt("exit_code")
        active.put("state", when (reason) {
            "cancelled", "timeout", "output_limit" -> reason
            "exited" -> if (code is Number && code.toInt() == 0) "completed" else "failed"
            "closed" -> "cancelled"
            else -> "interrupted"
        }).put("reason", reason).put("exit_code", code).put("finished_at", System.currentTimeMillis())
        if (record.getString("state") in liveStates) record.put("state", "ready")
        return true
    }
    private fun snapshot(record: JSONObject, historyLimit: Int = 20): JSONObject = JSONObject(record.toString()).apply {
        val all = getJSONArray("commands")
        put("commands", JSONArray((maxOf(0, all.length() - historyLimit) until all.length()).map { all.getJSONObject(it) }))
        put("command_count", all.length())
    }
    suspend fun list(chat: String): List<JSONObject> = withContext(Dispatchers.IO) { mutex.withLock {
        records.values.filter { it.getString("chat_id") == chat }.map { snapshot(it, 1) }
    } }

    suspend fun execute(chat: String, args: JSONObject): String = withContext(Dispatchers.IO) {
        val action = args.optString("action")
        try {
            val (validated, params) = TerminalContract.validate(args)
            var response = mutex.withLock {
                when (validated) {
                    "open" -> open(chat, params.optString("title", "终端"))
                    "list" -> JSONObject().put("terminals", JSONArray(records.values.filter { it.getString("chat_id") == chat }.map { snapshot(it, 1) }))
                        .put("background_policy", TerminalBackgroundPolicy.status(context))
                    else -> {
                        val record = owned(chat, params.getString("terminal_id"))
                        if (reconcile(record)) persist()
                        when (validated) {
                            "attach" -> snapshot(record)
                            "run" -> submit(record, params)
                            "read" -> read(record, params)
                            "input" -> {
                                requireLive(record)
                                val cmd = command(record, params.getString("command_id"))
                                if (cmd.getString("state") != "running") throw TerminalProblem("not_running", "命令已结束")
                                val input = File(folder(record), "input")
                                if (input.exists()) throw TerminalProblem("busy", "上次输入尚未消费")
                                atomic(input, JSONObject().put("id", cmd.getString("command_id")).put("text", params.getString("text")))
                                JSONObject().put("state", "input_submitted")
                            }
                            "cancel" -> {
                                requireLive(record)
                                val cmd = command(record, params.getString("command_id"))
                                if (cmd.getString("state") != "running") throw TerminalProblem("not_running", "命令已结束")
                                val cancel = File(folder(record), "cancel")
                                val temp = File(folder(record), "cancel.tmp")
                                temp.writeText(cmd.getString("command_id")); check(temp.renameTo(cancel)) { "停止请求提交失败" }
                                JSONObject().put("state", "cancel_requested").put("command_id", cmd.getString("command_id"))
                            }
                            "close" -> { close(record); persist(); stopServiceIfIdle(); snapshot(record) }
                            "clear_history" -> {
                                if (active(record) != null) throw TerminalProblem("busy", "运行中的命令不能清理日志")
                                val commands = File(folder(record), "commands")
                                check(!commands.exists() || commands.deleteRecursively()) { "命令日志清理失败，记录已保留" }
                                check(commands.mkdirs() || commands.isDirectory) { "无法创建命令目录" }
                                File(folder(record), "output.bin").writeText("")
                                record.put("commands", JSONArray()); persist(); snapshot(record)
                            }
                            "delete" -> {
                                if (record.getString("state") in liveStates) throw TerminalProblem("busy", "请先关闭终端，再删除历史")
                                val root = folder(record)
                                repeat(5) { if (root.exists() && !root.deleteRecursively()) Thread.sleep(50) }
                                check(!root.exists()) { "终端历史删除失败" }
                                records.remove(record.getString("terminal_id")); persist()
                                JSONObject().put("state", "deleted")
                            }
                            else -> error("Unknown validated action")
                        }
                    }
                }
            }
            // Short optional wait does not hold the manager lock or own command lifetime.
            if (validated == "run") {
                val until = System.nanoTime() + TerminalContract.integer(params, "wait_ms", 1000, 0, 10_000) * 1_000_000L
                while (System.nanoTime() < until) {
                    delay(50)
                    val current = mutex.withLock {
                        val record = owned(chat, params.getString("terminal_id"))
                        if (reconcile(record)) persist()
                        JSONObject(command(record, response.getString("command_id")).toString())
                    }
                    response.put("command", current)
                    if (current.getString("state") != "running") break
                }
            }
            if (validated == "run") {
                // Return the same bounded, UTF-8-safe output snapshot as read, including
                // failures and partial output. next_offset continues without replaying it.
                response = mutex.withLock {
                    val record = owned(chat, params.getString("terminal_id"))
                    if (reconcile(record)) persist()
                    val commandId = response.getString("command_id")
                    read(record, JSONObject().put("command_id", commandId))
                        .put("command_id", commandId)
                }
            }
            if (validated == "read") {
                val until = System.nanoTime() + TerminalContract.integer(params, "wait_ms", 0, 0, 20_000) * 1_000_000L
                while (response.optString("output").isEmpty() && !response.getBoolean("complete") && System.nanoTime() < until) {
                    delay(50)
                    response = mutex.withLock {
                        val record = owned(chat, params.getString("terminal_id"))
                        if (reconcile(record)) persist()
                        read(record, params)
                    }
                }
            }
            JSONObject().put("action", action).put("status", "ok").put("data", response).toString()
        } catch (cancel: CancellationException) { throw cancel }
        catch (problem: TerminalProblem) { JSONObject().put("action", action).put("status", problem.status).put("message", problem.message).toString() }
        catch (bad: IllegalArgumentException) { JSONObject().put("action", action).put("status", "invalid_argument").put("message", bad.message).toString() }
        catch (error: Exception) { JSONObject().put("action", action).put("status", "error").put("message", error.message?.take(500)).toString() }
    }

    private suspend fun open(chat: String, title: String): JSONObject {
        if (!runtime.ready) throw TerminalProblem("unavailable", runtime.status())
        if (processes.size >= 8) throw TerminalProblem("busy", "最多同时开启8个终端，请关闭空闲终端")
        if (records.values.count { it.getString("chat_id") == chat } >= 32) throw TerminalProblem("busy", "会话终端历史已达32个，请先关闭并delete旧终端")
        ensureService()
        val id = UUID.randomUUID().toString()
        val record = JSONObject().put("terminal_id", id).put("chat_id", chat).put("title", title)
            .put("state", "starting").put("created_at", System.currentTimeMillis()).put("commands", JSONArray())
        val root = folder(record).apply { mkdirs() }
        File(root, "commands").mkdirs()
        context.assets.open("runtime/terminal_supervisor.py").use { input -> File(root, "supervisor.py").outputStream().use { input.copyTo(it) } }
        records[id] = record
        try {
            val process = runtime.processBuilder(chat, root, listOf("/usr/bin/python3", "/run/capri/supervisor.py"))
                .redirectErrorStream(true).redirectOutput(File(root, "supervisor.log")).start()
            processes[id] = process
            persist()
            val ready = withTimeoutOrNull(30_000) {
                while (readJson(File(root, "state.json"))?.optString("state") != "ready") {
                    check(process.isAlive) { "终端启动失败：" + File(root, "supervisor.log").readText().take(1000) }
                    delay(25)
                }
                true
            }
            if (ready != true) throw TerminalProblem("timeout", "会话环境初始化或PTY Shell启动超时")
            val state = readJson(File(root, "state.json"))!!
            record.put("state", "ready").put("shell_pid", state.getLong("shell_pid"))
            persist()
            return snapshot(record)
        } catch (error: Exception) {
            processes.remove(id)?.destroyForcibly()
            endRecord(record, "error", error.message ?: "启动失败"); persist(); stopServiceIfIdle()
            throw error
        }
    }
    private fun submit(record: JSONObject, params: JSONObject): JSONObject {
        requireLive(record)
        if (active(record) != null) throw TerminalProblem("busy", "终端已有运行命令；请先读取、输入或取消，或另开终端")
        if (record.getJSONArray("commands").length() >= 200) throw TerminalProblem("busy", "命令历史达到200条，请先clear_history")
        val id = UUID.randomUUID().toString()
        val folder = commandFolder(record, id).apply { mkdirs() }
        File(folder, "main.sh").writeText(params.getString("command") + "\n")
        val cmd = JSONObject().put("command_id", id).put("state", "running").put("started_at", System.currentTimeMillis())
            .put("preview", params.getString("command").take(160))
            .put("timeout_seconds", TerminalContract.integer(params, "timeout_seconds", 60, 1, 86_400))
        record.getJSONArray("commands").put(cmd); record.put("state", "running"); persist()
        updateWakeLock(renew = true)
        try { atomic(File(this.folder(record), "request.json"), JSONObject().put("id", id).put("timeout_seconds", cmd.getInt("timeout_seconds"))) }
        catch (error: Exception) { cmd.put("state", "failed").put("reason", "提交失败"); record.put("state", "ready"); persist(); throw error }
        return JSONObject().put("terminal_id", record.getString("terminal_id")).put("command_id", id).put("command", JSONObject(cmd.toString()))
    }
    private fun read(record: JSONObject, params: JSONObject): JSONObject {
        val cmd = if (params.has("command_id")) command(record, params.getString("command_id")) else null
        val file = if (cmd == null) File(folder(record), "output.bin") else File(commandFolder(record, cmd.getString("command_id")), "output.bin")
        val offset = TerminalContract.integer(params, "offset", 0, 0, 2_000_000)
        val limit = TerminalContract.integer(params, "limit", 16_000, 4, 32_000)
        val size = file.length()
        require(offset <= size) { "offset超出日志长度；清理后请从0读取" }
        val bytes = if (file.exists()) RandomAccessFile(file, "r").use { f ->
            f.seek(offset.toLong()); ByteArray(minOf(limit.toLong(), size - offset).toInt()).also { f.readFully(it) }
        } else ByteArray(0)
        val ended = if (cmd != null) cmd.getString("state") != "running" else record.getString("state") !in liveStates
        val decoded = TerminalOutput.decode(bytes, ended && offset.toLong() + bytes.size == size)
        return JSONObject().put("terminal_id", record.getString("terminal_id")).put("terminal_state", record.getString("state"))
            .put("command", cmd?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .put("output", decoded.text).put("offset", offset).put("next_offset", offset + decoded.consumed)
            .put("total_bytes", size).put("has_more", offset + decoded.consumed < size).put("complete", ended)
    }
    private fun close(record: JSONObject) {
        val id = record.getString("terminal_id")
        // An already closed/interrupted record may outlive a partially deleted log directory.
        val root = folder(record)
        if (root.isDirectory) File(root, "close").writeText("")
        processes.remove(id)?.let { process ->
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); process.waitFor(500, TimeUnit.MILLISECONDS) }
        }
        endRecord(record, "closed", "用户关闭终端")
        updateWakeLock()
    }
}
