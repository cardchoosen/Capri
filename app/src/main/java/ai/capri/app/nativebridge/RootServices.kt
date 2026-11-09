package ai.capri.app.nativebridge

import ai.capri.app.HistoryEditing
import ai.capri.app.RuntimeGate
import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.util.UUID

/** Root services are explicit, bounded resources, never detached shell escape hatches. */
internal class RootServices private constructor(private val context: Context) {
    companion object {
        private val instances = mutableMapOf<String, RootServices>()
        @Synchronized fun get(context: Context): RootServices = instances.getOrPut(context.filesDir.canonicalPath) { RootServices(context.applicationContext) }
    }
    private val root = RootRuntime(context)
    private val directory = File(context.filesDir, "root-services").apply { mkdirs() }
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private data class Live(val owner: String, val process: Process, val expires: Long, val remote: JSONObject?)
    private val live = mutableMapOf<String, Live>()
    private fun save(dir: File, value: JSONObject) {
        val atomic = AtomicFile(File(dir, "receipt.json")); val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    private fun receipt(dir: File) = JSONObject(AtomicFile(File(dir, "receipt.json")).openRead().bufferedReader().use { it.readText() })
    private fun dirs() = directory.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) }
    private fun selected(owner: String, id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid service_id." }
        return File(directory, id).also { require(it.isDirectory && receipt(it).getString("owner") == owner) { "Service not found in this history scope." } }
    }
    init {
        wake.trySend(Unit)
        scope.launch {
            for (ignored in wake) {
                val running=lock.withLock {
                    // Restart never adopts a PID or replays a command. Verify identity before cleanup.
                    for (dir in dirs()) runCatching {
                        val r = receipt(dir); val entry = live[dir.name]
                        if (r.optString("state") in setOf("starting", "running") &&
                            (entry == null || !entry.process.isAlive || SystemClock.elapsedRealtime() >= entry.expires || File(dir, "done").length() > 0)) {
                            stopLocked(dir, if (entry == null) "interrupted" else if (File(dir,"done").length()>0) "exited" else "expired")
                        }
                    }
                    live.keys.any { receipt(File(directory,it)).optString("state")=="running" }
                }
                // No idle polling, and no automatic repeated su prompts after unconfirmed cleanup.
                if(running) { delay(1000);wake.trySend(Unit) }
            }
        }
    }
    private fun view(dir: File): JSONObject {
        val r = receipt(dir)
        val output = File(dir, "stdout"); val errors = File(dir, "stderr")
        fun tail(file: File) = if (!file.isFile) "" else java.io.RandomAccessFile(file,"r").use { f ->
            f.seek((f.length()-6000).coerceAtLeast(0)); val bytes=ByteArray((f.length()-f.filePointer).toInt());f.readFully(bytes);String(bytes,Charsets.UTF_8)
        }
        val token = live[dir.name]?.remote?.optString("token").orEmpty()
        fun redact(text:String)=if(token.isEmpty())text else text.replace(token,"[redacted]")
        val code=File(dir,"done").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        return JSONObject(r.toString()).apply {
            remove("owner");remove("signature");remove("request_key");remove("host_directory")
            put("stdout",redact(tail(output)));put("stderr",redact(tail(errors)))
            put("output_truncated",output.length()>6000 || errors.length()>6000)
            put("exit_code",code?:JSONObject.NULL)
            put("goal_verified",false)
            put("notice","Service lifecycle only. A running process does not prove client connectivity, target attachment or script execution.")
        }
    }
    private fun response(dir:File):JSONObject {
        val data=view(dir)
        val status=when {
            data.optString("state")=="cleanup_unconfirmed" -> "cleanup_unconfirmed"
            data.optString("state")=="failed" || data.has("exit_code") && !data.isNull("exit_code") && data.optInt("exit_code")!=0 -> "failed"
            else -> "ok"
        }
        return JSONObject().put("status",status).put("data",data)
    }
    suspend fun execute(owner: String, workspace: File, params: JSONObject, frida: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        lock.withLock {
            when (val operation=params.requiredText("operation",20)) {
                "start" -> start(owner,workspace,params,frida)
                "list" -> JSONObject().put("status","ok").put("services",JSONArray(dirs().filter { receipt(it).optString("owner")==owner }.map(::view)))
                "status", "stop" -> {
                    val dir=selected(owner,params.requiredText("service_id",36))
                    if(operation=="stop")stopLocked(dir,"stopped")
                    else if(live[dir.name]?.let { !it.process.isAlive || SystemClock.elapsedRealtime()>=it.expires } != false && receipt(dir).optString("state")=="running")
                        stopLocked(dir,if(live[dir.name]?.let { SystemClock.elapsedRealtime()>=it.expires }==true)"expired" else "interrupted")
                    response(dir)
                }
                else -> throw IllegalArgumentException("Unknown service operation: $operation")
            }
        }
    }
    private suspend fun start(owner: String, workspace: File, params: JSONObject, frida: Boolean): JSONObject {
        if(root.snapshot().optString("app_authorization")!="granted") throw NativeFailure("permission_required","Root authorization required; use root_request first.")
        val su=root.suPath()?:error("Host su is unavailable.")
        require(File("/system/bin/setsid").canExecute()) { "Host setsid is unavailable." }
        val key=params.requiredText("request_key",80)
        val signature=params.keys().asSequence().toList().sorted().joinToString("\n") { key -> key+"="+JSONObject.quote(params.get(key).toString()) } // Stable across JSON key order.
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(""){"%02x".format(it)}
        dirs().firstOrNull { receipt(it).let { r -> r.optString("owner")==owner && r.optString("request_key")==key } }?.let { dir ->
            require(receipt(dir).optString("signature")==hash) { "request_key conflict; inspect the original service." }
            return response(dir).put("reused",true)
        }
        require(dirs().size < 128) { "Root service receipt capacity reached; existing receipts are retained for deduplication." }
        require(live.size < 2 && live.values.none { it.owner==owner }) { "A service is already active in this scope, or the device service limit was reached." }
        val spec=if(frida) RootServiceProtocol.request(JSONObject().put("request_key",key).put("command","true").put("lifetime_seconds",params.opt("lifetime_seconds")?:180)) else RootServiceProtocol.request(params)
        val id=UUID.randomUUID().toString();val dir=File(directory,id).apply { mkdirs() }
        var process:Process?=null;var accepted=false
        val r=JSONObject().put("service_id",id).put("owner",owner).put("request_key",key).put("signature",hash)
            .put("kind",if(frida)"frida" else "command").put("state","starting").put("created_at",System.currentTimeMillis())
            .put("expires_at",System.currentTimeMillis()+spec.lifetime*1000L).put("cleanup_verified",false)
        var remote:JSONObject?=null
        try {
            var command=spec.command
            if(frida) {
                val input=params.requiredText("server_path",2000).removePrefix("/workspace/")
                require(!File(input).isAbsolute && input.split('/').none { it==".." }) { "server_path must be inside this workspace." }
                val source=File(workspace,input).canonicalFile
                require(source.toPath().startsWith(workspace.canonicalFile.toPath()) && source.isFile && source.length() in 1..160_000_000) { "Invalid workspace Frida server binary." }
                val pinned=JSONObject(context.assets.open("rea/frida-package.json").bufferedReader().use { it.readText() }).getJSONObject("dependencies").getString("frida")
                val installed=File(context.filesDir,"runtime/ubuntu/opt/capri/rea/frida/node_modules/frida/package.json")
                require(installed.isFile && JSONObject(installed.readText()).getString("version")==pinned) { "Prepare the managed REA runtime profile first; Frida client version must match the pinned version." }
                val binary=File(dir,"server-input")
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                source.inputStream().use { inputStream -> binary.outputStream().use { output ->
                    val buffer=ByteArray(65536);var bytes=0L
                    while(true) { val count=inputStream.read(buffer);if(count<0)break;bytes+=count
                        require(bytes<=160_000_000) { "Frida server exceeds the size limit." }
                        digest.update(buffer,0,count);output.write(buffer,0,count)
                    }
                } }
                val port=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
                remote=JSONObject().put("address","127.0.0.1:$port").put("token",UUID.randomUUID().toString()+UUID.randomUUID())
                val temp="/data/local/tmp/capri-frida-$id"
                r.put("address",remote.getString("address")).put("client_version",pinned).put("server_sha256",digest.digest().joinToString(""){"%02x".format(it)})
                command="""
                    umask 077
                    mkdir ${RootProtocol.quote(temp)} || exit 71
                    cp ${RootProtocol.quote(binary.path)} ${RootProtocol.quote("$temp/server")} || exit 72
                    chmod 700 ${RootProtocol.quote("$temp/server")} || exit 73
                    version=${'$'}(${RootProtocol.quote("$temp/server")} --version) || exit 74
                    [ "${'$'}version" = ${RootProtocol.quote(pinned)} ] || { printf 'Frida server version mismatch\n' >&2; exit 75; }
                    printf '%s\n' "${'$'}version" > ${RootProtocol.quote(File(dir,"server-version").path)}
                    exec ${RootProtocol.quote("$temp/server")} --listen ${RootProtocol.quote(remote.getString("address"))} --token ${RootProtocol.quote(remote.getString("token"))} --disable-preload --ignore-crashes
                """.trimIndent()
                r.put("host_directory",temp)
            }
            save(dir,r) // Durable intent before any privileged launch.
            listOf("identity","release","done","stdout","stderr","stop","server-version").forEach { File(dir,it).writeText("") }
            File(dir,"command.sh").writeText(command+"\n")
            var supervisor=RootServiceProtocol.supervisor(dir,workspace,spec.lifetime)
            // Watchdog owns cleanup even after the app dies; never use user-supplied paths here.
            if(frida)supervisor=supervisor.replace("printf expired >", "rm -rf ${RootProtocol.quote(r.getString("host_directory"))}\n                printf expired >")
            File(dir,"supervisor.sh").writeText(supervisor)
            r.put("launch_attempted",true);save(dir,r)
            process=RuntimeGate.start(context,HistoryEditing.scope(owner).first,"Root service") { root.builder(RootProtocol.launch(su,dir),dir,File(dir,"launcher.log")).start() }
            process.outputStream.close()
            live[id]=Live(owner,process,SystemClock.elapsedRealtime()+spec.lifetime*1000L,remote)
            withTimeout(5000) { while(root.identity(dir)==null && process.isAlive)delay(25) }
            require(root.identity(dir)!=null) { "Host root identity was not verified; service was not released." }
            live[id]=Live(owner,process,SystemClock.elapsedRealtime()+spec.lifetime*1000L,remote)
            File(dir,"release").writeText("go\n")
            if(frida)withTimeout(10000) { while(File(dir,"server-version").length()==0L && File(dir,"done").length()==0L && process.isAlive)delay(25) }
            delay(250)
            if(File(dir,"done").length()>0 || !process.isAlive) {
                stopLocked(dir,"failed")
                return JSONObject().put("status","failed").put("stage","server_start").put("data",view(dir))
            }
            r.put("state","running").put("current_command_verified",true).put("server_version_verified",frida && File(dir,"server-version").readText().trim()==r.optString("client_version"));save(dir,r)
            currentCoroutineContext().ensureActive();accepted=true;wake.trySend(Unit)
            return JSONObject().put("status","ok").put("stage","server_started").put("data",view(dir))
        } finally {
            if(!accepted)withContext(NonCancellable) {
                if(File(dir,"receipt.json").exists())stopLocked(dir,"interrupted")
                else process?.destroyForcibly()
            }
        }
    }
    private suspend fun stopLocked(dir:File, state:String) {
        val r=receipt(dir)
        if(r.optBoolean("cleanup_verified"))return
        File(dir,"stop").writeText("stop\n")
        val su=root.suPath()
        val verified=if(root.identity(dir)==null)!r.optBoolean("launch_attempted") else su!=null && root.cleanup(su,dir)
        if(!verified)live[dir.name]?.process?.let { if(it.isAlive)it.destroyForcibly() }
        if(verified) {
            live.remove(dir.name)?.process?.let { if(it.isAlive)it.destroyForcibly() }
            if(r.optString("kind")=="frida" && su!=null) {
                val temp="/data/local/tmp/capri-frida-${dir.name}"
                val p=root.builder(listOf(su,"-c","rm -rf ${RootProtocol.quote(temp)}"),dir,File(dir,"cleanup-path.log")).start()
                try { withTimeoutOrNull(3000) { while(p.isAlive)delay(25) } } finally { if(p.isAlive)p.destroyForcibly() }
            }
            File(dir,"command.sh").delete();File(dir,"server-input").delete()
        }
        r.put("state",if(verified)state else "cleanup_unconfirmed").put("cleanup_verified",verified);save(dir,r)
    }
    suspend fun remote(owner:String):JSONObject?=lock.withLock {
        val entry=live.values.singleOrNull { it.owner==owner && it.remote!=null && it.process.isAlive && SystemClock.elapsedRealtime()<it.expires }
        entry?.remote?.let { JSONObject(it.toString()) }
    }
    suspend fun stopOwner(owner:String, chat:Boolean=false)=withContext(NonCancellable+Dispatchers.IO) { lock.withLock {
        dirs().filter { receipt(it).optString("owner").let { scope -> if(chat)HistoryEditing.scope(scope).first==owner else scope==owner } }
            .filter { !receipt(it).optBoolean("cleanup_verified") }.forEach { stopLocked(it,"stopped") }
    } }
    suspend fun stopAll()=withContext(NonCancellable+Dispatchers.IO) { lock.withLock {
        dirs().filter { !receipt(it).optBoolean("cleanup_verified") }.forEach { stopLocked(it,"stopped") }
    } }
    fun requestStopAll() { scope.launch { stopAll() } }
}
