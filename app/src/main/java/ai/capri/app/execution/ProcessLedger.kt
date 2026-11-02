package ai.capri.app.execution

import android.content.Context
import android.os.FileObserver
import ai.capri.app.OwnedProcesses
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Intent precedes launch. Receipts never reattach PIDs or replay effects. One event waiter per live process. */
internal class ProcessLedger(private val root: File, private val stop: (Process) -> Unit = OwnedProcesses::stop, private val invalidate: () -> Unit = {}) {
    companion object {
        private val stopReasons = ConcurrentHashMap<Process,String>()
        fun markStopping(process: Process, reason: String) { if (finishers.containsKey(process)) stopReasons.putIfAbsent(process,reason) }
        private val finishers = ConcurrentHashMap<Process, () -> Unit>()
        fun processStopped(process: Process) { if (!process.isAlive) finishers[process]?.invoke() }
        private val instances = ConcurrentHashMap<String, ProcessLedger>()
        @Synchronized fun get(context: Context) = instances.getOrPut(context.filesDir.canonicalPath) { ProcessLedger(File(context.filesDir,"process-ledger"), invalidate = { ai.capri.app.environment.InstallationEnvironment.get(context).invalidate("ubuntu_shared", "An opaque execution may have changed dependencies; reuse requires fresh evidence.") }) }
        fun forgetIfLoaded(files: File, chat: String) { instances[files.canonicalPath]?.forget(chat) }
        private val active = setOf("starting", "running", "cancel_requested")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val records = linkedMapOf<String, JSONObject>()
    private val live = mutableMapOf<String, Process>()
    private val watchers = mutableMapOf<String,FileObserver>()
    val updates = MutableStateFlow(0L)
    init {
        root.mkdirs()
        root.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }.filter { it.extension == "json" }.sortedBy { it.lastModified() }.forEach { file ->
            runCatching {
                val record = JSONObject(file.readText()); val id = record.getString("process_id")
                require(file.name == "$id.json" && UUID.fromString(id).toString() == id)
                if (record.getString("state") in active) {
                    record.put("state", "interrupted").put("effect", "unknown").put("reason", "Application process ended; no PID reattachment or automatic replay.")
                    record.put("finished_at", System.currentTimeMillis()); save(record)
                }
                records[id] = record
            }
        }
    }
    private fun save(record: JSONObject) {
        val folder = File(root, record.getString("chat_id")).apply { mkdirs() }
        val target = File(folder,record.getString("process_id") + ".json")
        val temp = File(root,target.name + ".tmp")
        java.io.FileOutputStream(temp).use { it.write(record.toString().toByteArray()); it.fd.sync() }
        Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }
    @Synchronized fun launch(chat: String, kind: String, command: List<String>, output: File?, changesEnvironment: Boolean = false, backend: ExecutionBackend = LocalExecutionBackend, launch: () -> Process): Process {
        require(chat.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid chat identity." }
        // Retain bounded history, but never evict a live operation.
        while (records.size >= 256 || retainedBytes() > 64L * 1024 * 1024) {
            val old = records.entries.firstOrNull { it.value.getString("state") !in active } ?: error("Process history capacity reached; wait for active operations.")
            check(File(File(root,old.value.getString("chat_id")),old.key+".json").delete()) { "Unable to retire process receipt." }; File(File(root,old.value.getString("chat_id")),old.key+".output").delete(); records.remove(old.key)
        }
        val id = UUID.randomUUID().toString()
        val record = JSONObject().put("process_id",id).put("chat_id",chat).put("kind", when(kind) { "脚本" -> "script"; "会话准备" -> "session_prepare"; "持续终端" -> "terminal"; "Coding diagnostics" -> "coding_diagnostics"; "Program kernel" -> "program_kernel"; else -> kind }).put("backend",backend.id)
            // Do not copy raw argv: it can contain credentials, inline source or user data.
            .put("executable",command.firstOrNull()?.take(200) ?: "unspecified").put("argument_count",command.size)
            .put("output_path",output?.absolutePath ?: JSONObject.NULL).put("state","starting")
            .put("started_at",System.currentTimeMillis()).put("cancel_action","terminal.process_cancel")
        if (changesEnvironment) invalidate()
        save(record); records[id] = record; updates.value++
        val process = try { backend.start(launch) } catch (error: Throwable) {
            record.put("state","launch_failed").put("effect","unknown").put("finished_at",System.currentTimeMillis()); save(record); updates.value++; throw error
        }
        live[id] = process; record.put("state","running")
        try { save(record) } catch (error: Throwable) { stop(process); live.remove(id); record.put("state","interrupted").put("effect","unknown"); runCatching { save(record) }; updates.value++; throw error }
        if (output != null) {
            val watcher = object : FileObserver(output.parentFile!!.path, FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == output.name && output.length() > 2_000_000) scope.launch { markStopping(process,"output_limit"); stop(process) }
                }
            }
            watchers[id] = watcher; watcher.startWatching()
        }
        val finish = {
            synchronized(this@ProcessLedger) {
                if (records[id] === record && live[id] === process && !process.isAlive) {
                    watchers.remove(id)?.stopWatching()
                    live.remove(id); finishers.remove(process)
                    if (changesEnvironment) runCatching { invalidate() }
                    val code = runCatching { process.exitValue() }.getOrNull()
                    val reason = stopReasons.remove(process)
                    val cancelled = record.optString("state") == "cancel_requested"
                    val total = output?.length() ?: 0L
                    record.put("state",if (total > 2_000_000) "output_limit" else if (cancelled) "cancelled" else if (reason != null) reason else if (code == 0) "completed" else if (code == null) "interrupted" else "failed")
                        .put("exit_code",code ?: JSONObject.NULL).put("finished_at",System.currentTimeMillis())
                        .put("effect", "not_independently_verified").put("source_output_bytes",total)
                    if (output?.isFile == true) {
                        try {
                            val frozen = File(File(root,chat),"$id.output")
                            output.inputStream().use { input -> java.io.FileOutputStream(frozen).use { target ->
                                val buffer=ByteArray(8192); var remaining=2_000_000
                                while (remaining > 0) { val n=input.read(buffer,0,minOf(buffer.size,remaining)); if(n<0)break; target.write(buffer,0,n);remaining-=n }
                                target.fd.sync()
                            } }
                            record.put("output_path",frozen.absolutePath).put("output_truncated",total>2_000_000)
                        } catch (_: Exception) { record.put("output_path",JSONObject.NULL).put("output_capture_failed",true) }
                    }
                    record.put("receipt_durable",true)
                    try { save(record) } catch (_: Exception) { record.put("receipt_durable",false) }
                    updates.value++
                }
            }
            Unit
        }
        finishers[process] = finish
        scope.launch { try { process.waitFor() } finally { finish() } }
        return process
    }
    private fun retainedBytes(): Long = root.listFiles().orEmpty().sumOf { folder -> folder.listFiles().orEmpty().filter { it.extension == "output" }.sumOf { it.length() } }
    @Synchronized private fun forget(chat: String) {
        check(records.none { (id,record) -> record.optString("chat_id") == chat && live[id]?.isAlive == true }) { "Cannot delete live process records." }
        live.entries.filter { records[it.key]?.optString("chat_id") == chat }.forEach { finishers.remove(it.value); stopReasons.remove(it.value); watchers.remove(it.key)?.stopWatching() }
        live.entries.removeAll { records[it.key]?.optString("chat_id") == chat }
        records.entries.removeAll { it.value.optString("chat_id") == chat }; updates.value++
    }
    @Synchronized fun list(chat: String): JSONArray = JSONArray(records.values.filter { it.getString("chat_id") == chat }.takeLast(16).map(::publicRecord))
    @Synchronized fun idOf(process: Process): String? = live.entries.firstOrNull { it.value === process }?.key
    @Synchronized fun count(chat: String) = records.values.count { it.optString("chat_id") == chat }
    private fun owned(chat: String,id: String) = records[id]?.takeIf { it.getString("chat_id") == chat } ?: error("Process not found in the current chat.")
    private fun publicRecord(source: JSONObject) = JSONObject(source.toString()).apply { remove("output_path") }
    @Synchronized fun read(chat: String,id: String,offset: Long,limit: Int): JSONObject {
        val record = owned(chat,id)
        return OutputPage.read(record.optString("output_path").takeIf { !record.isNull("output_path") }?.let(::File),offset,limit,record.getString("state") !in active)
            .put("process",publicRecord(record))
    }
    @Synchronized fun cancel(chat: String,id: String): JSONObject {
        val record = owned(chat,id)
        val process = live[id] ?: return publicRecord(record)
        if (!process.isAlive) return publicRecord(record)
        record.put("state","cancel_requested"); save(record); updates.value++
        stop(process)
        return publicRecord(record)
    }
    @Synchronized fun signal() { updates.value++ }
    suspend fun awaitChange(after: Long,waitMs: Long): Long {
        require(waitMs in 0..20000)
        withTimeoutOrNull(waitMs) { updates.first { it != after } }
        return updates.value
    }
}
