package ai.capri.host

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Sandboxed command execution for the service host. Each approval binds the exact command and cwd. */
internal class HostExecution(private val data: Path) : AutoCloseable {
    private val stateFile = data.resolve("host-execution.json")
    private val processes = ConcurrentHashMap<String, Running>()

    private data class Running(
        val id: String, val session: String, val task: String, val process: Process, val temp: Path,
        val stdout: StringBuilder = StringBuilder(), val stderr: StringBuilder = StringBuilder(),
        val streamsDone: CountDownLatch = CountDownLatch(2),
        val settled: AtomicBoolean = AtomicBoolean(false),
        @Volatile var truncatedOut: Boolean = false, @Volatile var truncatedErr: Boolean = false,
    )

    init {
        val state = read()
        val receipts = state.optJSONArray("receipts") ?: JSONArray()
        for (index in 0 until receipts.length()) {
            val receipt = receipts.getJSONObject(index)
            if (receipt.optString("state") == "running") receipt.put("state", "interrupted")
                .put("finishedAt", System.currentTimeMillis())
        }
        state.put("receipts", receipts)
        save(state)
    }

    @Synchronized private fun read(): JSONObject = if (Files.isRegularFile(stateFile, NOFOLLOW_LINKS))
        JSONObject(Files.readString(stateFile)) else JSONObject()

    @Synchronized private fun save(value: JSONObject) {
        Files.createDirectories(data, privateDirectory)
        val temp = Files.createTempFile(data, ".host-execution-", ".tmp", privateFile)
        try {
            Files.writeString(temp, value.toString())
            Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
    }

    @Synchronized fun settings(session: String? = null): JSONObject {
        val state = read()
        val policy = state.optJSONObject("policy") ?: JSONObject()
        val pending = jsonObjects(state.optJSONArray("approvals") ?: JSONArray()).filter { session == null || it.optString("session") == session }
        val receipts = jsonObjects(state.optJSONArray("receipts") ?: JSONArray()).filter { session == null || it.optString("session") == session }
        return JSONObject().put("mode", policy.optString("mode", "approval"))
            .put("allowlist", JSONArray(jsonStrings(policy.optJSONArray("allowlist"))))
            .put("sandbox", sandboxBackend()).put("approvals", JSONArray(pending.map {
                JSONObject().put("id", it.getString("id")).put("session", it.getString("session"))
                    .put("command", it.getString("command")).put("cwd", it.getString("cwd"))
                    .put("createdAt", it.getLong("createdAt"))
            })).put("receipts", JSONArray(receipts.takeLast(20).map {
                JSONObject().put("id", it.getString("id")).put("state", it.getString("state"))
                    .put("command", it.getString("command")).put("cwd", it.getString("cwd"))
                    .put("startedAt", it.optLong("startedAt")).putOpt("finishedAt", it.opt("finishedAt"))
                    .putOpt("exitCode", it.opt("exitCode"))
            }))
    }

    @Synchronized fun configure(payload: JSONObject) {
        val mode = payload.getString("mode")
        require(mode in setOf("disabled", "approval", "allowlist", "full")) { "Choose a supported execution mode." }
        val entries = payload.optJSONArray("allowlist") ?: JSONArray()
        require(entries.length() <= 100) { "The command allowlist can contain at most 100 entries." }
        val values = (0 until entries.length()).map { entries.getString(it).trim() }
            .filter(String::isNotEmpty).distinct()
        require(values.all { it.length <= 1000 && !it.contains('\u0000') }) { "Allowlist entries must be at most 1000 characters." }
        val state = read().put("policy", JSONObject().put("mode", mode).put("allowlist", JSONArray(values)))
        save(state)
    }

    @Synchronized fun decide(session: String, id: String, approve: Boolean): JSONObject {
        val state = read()
        val requests = state.optJSONArray("approvals") ?: JSONArray()
        val index = (0 until requests.length()).firstOrNull { requests.getJSONObject(it).optString("id") == id }
            ?: throw WorkspaceFailure("approval_not_found", "This execution approval is no longer available.")
        val request = requests.getJSONObject(index)
        if (request.optString("session") != session || System.currentTimeMillis() - request.optLong("createdAt") > 30 * 60_000L)
            throw WorkspaceFailure("approval_expired", "This execution approval has expired.")
        if (approve) {
            val grants = state.optJSONArray("grants") ?: JSONArray()
            grants.put(JSONObject().put("id", id).put("session", session).put("task", request.getString("task"))
                .put("signature", request.getString("signature")).put("createdAt", System.currentTimeMillis()))
            state.put("grants", grants)
        }
        requests.remove(index)
        state.put("approvals", requests)
        save(state)
        return JSONObject().put("status", if (approve) "approved" else "denied").put("command", request.getString("command"))
    }

    fun execute(session: String, task: String, workspace: Path, args: JSONObject, ask: (String) -> Unit): JSONObject = when (args.getString("action")) {
        "run" -> run(session, task, workspace, args, ask)
        "list" -> list(session)
        "read" -> readReceipt(session, args.getString("id"))
        "stop" -> stop(session, args.getString("id"))
        else -> throw WorkspaceFailure("invalid_action", "Choose run, list, read, or stop.")
    }

    private fun run(session: String, task: String, workspace: Path, args: JSONObject, ask: (String) -> Unit): JSONObject {
        val command = args.getString("command").trim()
        if (command.isEmpty()) throw WorkspaceFailure("empty_command", "Provide a command to run.")
        val root = workspace.toRealPath()
        val requested = args.optString("cwd", ".").ifBlank { "." }
        val cwd = root.resolve(requested).normalize()
        if (!cwd.startsWith(root) || !Files.isDirectory(cwd, NOFOLLOW_LINKS) || Files.isSymbolicLink(cwd) || !cwd.toRealPath().startsWith(root))
            throw WorkspaceFailure("execution_cwd_outside_workspace", "The working directory must be an existing directory inside this conversation workspace.")
        val backend = sandboxBackend()
        if (backend == "unavailable") throw WorkspaceFailure("sandbox_unavailable", "No supported OS sandbox is available; host execution is fail-closed.")
        val signature = sha256("$command\n${cwd.toRealPath()}\n$backend")
        val state = read()
        val policy = state.optJSONObject("policy") ?: JSONObject()
        val mode = policy.optString("mode", "approval")
        if (mode == "disabled") throw WorkspaceFailure("host_execution_disabled", "Host command execution is disabled in Settings.")
        val allowlisted = jsonStrings(policy.optJSONArray("allowlist")).any { it == command }
        if (mode != "full" && !(mode == "allowlist" && allowlisted)) {
            val grants = state.optJSONArray("grants") ?: JSONArray()
            val grantIndex = (0 until grants.length()).firstOrNull {
                val grant = grants.getJSONObject(it)
                grant.optString("session") == session && grant.optString("task") == task && grant.optString("signature") == signature &&
                    System.currentTimeMillis() - grant.optLong("createdAt") <= 30 * 60_000L
            }
            if (grantIndex == null) {
                val requests = state.optJSONArray("approvals") ?: JSONArray()
                val existing = (0 until requests.length()).map(requests::getJSONObject).firstOrNull {
                    it.optString("session") == session && it.optString("task") == task && it.optString("signature") == signature
                }
                val pending = existing ?: JSONObject().put("id", UUID.randomUUID().toString()).put("session", session)
                    .put("task", task).put("command", command).put("cwd", root.relativize(cwd).toString())
                    .put("signature", signature).put("createdAt", System.currentTimeMillis()).also(requests::put)
                state.put("approvals", requests)
                save(state)
                ask("Approval is required to run this command in the current conversation workspace. Review it in Settings, then continue the task.")
                return JSONObject().put("status", "waiting_for_user").put("approval", JSONObject()
                    .put("id", pending.getString("id")).put("command", command).put("cwd", pending.getString("cwd")))
            }
            grants.remove(grantIndex)
            state.put("grants", grants)
            save(state)
        }
        if (processes.size >= 3) throw WorkspaceFailure("host_execution_busy", "At most three host commands may run at once.")
        val id = UUID.randomUUID().toString()
        val temp = root.resolve(".capri-tmp-${UUID.randomUUID()}")
        Files.createDirectory(temp)
        val process = try { startSandboxed(command, cwd, root, temp, backend) }
        catch (failure: Exception) { Files.deleteIfExists(temp); throw failure }
        val running = Running(id, session, task, process, temp)
        processes[id] = running
        val startedAt = System.currentTimeMillis()
        persistReceipt(JSONObject().put("id", id).put("session", session).put("task", task).put("command", command)
            .put("cwd", root.relativize(cwd).toString()).put("state", "running").put("startedAt", startedAt)
            .put("backend", backend).put("commandHash", sha256(command)))
        drain(process.inputStream, running.stdout, running) { running.truncatedOut = true }
        drain(process.errorStream, running.stderr, running) { running.truncatedErr = true }
        thread(isDaemon = true) {
            if (process.waitFor(MAX_RUNTIME_MINUTES, TimeUnit.MINUTES)) settle(running, process.exitValue())
            else {
                stopTree(process)
                runCatching { process.waitFor(3, TimeUnit.SECONDS) }
                settle(running, 124)
            }
        }
        val timeout = args.optLong("timeoutMs", 15_000).coerceIn(250, 60_000)
        if (process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
            settle(running, process.exitValue())
            return readReceipt(session, id)
        }
        return JSONObject().put("status", "running").put("id", id).put("message", "Command is still running; use host_exec with action=read or stop.")
    }

    private fun startSandboxed(command: String, cwd: Path, root: Path, temp: Path, backend: String): Process {
        val shell = if (File("/bin/zsh").exists()) "/bin/zsh" else "/bin/bash"
        val argv = when (backend) {
            "macos-sandbox-exec" -> listOf("/usr/bin/sandbox-exec", "-p", macProfile(root, temp), shell, "-lc", command)
            "linux-bwrap" -> linuxSandboxArgs(findBwrap(), shell, command, cwd, root, temp)
            else -> throw WorkspaceFailure("sandbox_unavailable", "No supported OS sandbox is available; host execution is fail-closed.")
        }
        val builder = ProcessBuilder(argv).directory(cwd.toFile()).redirectInput(ProcessBuilder.Redirect.PIPE)
        val env = builder.environment()
        env.clear()
        env["PATH"] = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        env["HOME"] = root.toString()
        env["TMPDIR"] = temp.toString()
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        return builder.start().also { it.outputStream.close() }
    }

    private fun macProfile(root: Path, temp: Path): String {
        fun quote(path: Path) = "\"${path.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val readable = listOf("/System", "/usr", "/bin", "/sbin", "/Applications", "/Library/Developer", "/opt/homebrew", "/usr/local", "/dev")
            .joinToString(" ") { "(subpath \"$it\")" }
        return """
            (version 1)
            (deny default)
            (allow process*)
            (allow sysctl-read)
            (allow mach-lookup)
            (allow file-read* $readable (subpath ${quote(root)}))
            (allow file-write* (subpath ${quote(root)}) (subpath ${quote(temp)}))
            (allow file-map-executable)
            (allow file-read-metadata*)
        """.trimIndent()
    }

    private fun findBwrap(): String = listOf("/usr/bin/bwrap", "/bin/bwrap").firstOrNull { Files.isExecutable(Path.of(it)) }
        ?: throw WorkspaceFailure("sandbox_unavailable", "bubblewrap is not installed; host execution is fail-closed.")

    private fun linuxSandboxArgs(bwrap: String, shell: String, command: String, cwd: Path, root: Path, temp: Path): List<String> {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val args = mutableListOf(bwrap, "--die-with-parent", "--new-session", "--unshare-user", "--unshare-pid", "--unshare-ipc", "--unshare-net",
            "--ro-bind", "/", "/", "--tmpfs", home.toString())
        if (root.startsWith(home)) {
            var parent = home
            for (part in home.relativize(root.parent)) {
                parent = parent.resolve(part)
                args.addAll(listOf("--dir", parent.toString()))
            }
        }
        args.addAll(listOf("--bind", root.toString(), root.toString(), "--bind", temp.toString(), temp.toString(),
            "--tmpfs", "/tmp", "--tmpfs", "/var/tmp", "--dev", "/dev", "--proc", "/proc",
            "--chdir", cwd.toString(), "--", shell, "-lc", command))
        return args
    }

    private fun drain(stream: java.io.InputStream, target: StringBuilder, running: Running, truncated: () -> Unit) = thread(isDaemon = true) {
        try {
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                synchronized(target) {
                    val remaining = OUTPUT_LIMIT - target.length
                    if (remaining > 0) target.append(String(buffer, 0, minOf(count, remaining), Charsets.UTF_8))
                    if (count > remaining.coerceAtLeast(0)) truncated()
                }
            }
        } finally { running.streamsDone.countDown() }
    }

    private fun settle(running: Running, exit: Int) {
        if (!running.settled.compareAndSet(false, true)) return
        running.streamsDone.await(1, TimeUnit.SECONDS)
        processes.remove(running.id)
        val state = read()
        val rows = state.optJSONArray("receipts") ?: JSONArray()
        val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).optString("id") == running.id } ?: return
        val row = rows.getJSONObject(index)
        row.put("state", when (exit) { 0 -> "completed"; 143 -> "cancelled"; 124 -> "timed_out"; else -> "failed" })
            .put("exitCode", exit).put("finishedAt", System.currentTimeMillis())
            .put("stdout", synchronized(running.stdout) { running.stdout.toString() })
            .put("stderr", synchronized(running.stderr) { running.stderr.toString() })
            .put("stdoutTruncated", running.truncatedOut).put("stderrTruncated", running.truncatedErr)
        save(state)
        runCatching { Files.deleteIfExists(running.temp) }
    }

    private fun persistReceipt(receipt: JSONObject) {
        val state = read(); val rows = state.optJSONArray("receipts") ?: JSONArray()
        rows.put(receipt); while (rows.length() > 200) rows.remove(0)
        state.put("receipts", rows); save(state)
    }

    @Synchronized private fun list(session: String) = JSONObject().put("status", "ok").put("receipts", JSONArray(
        jsonObjects(read().optJSONArray("receipts")).filter { it.optString("session") == session }.takeLast(30).map(::publicReceipt)))

    @Synchronized private fun readReceipt(session: String, id: String): JSONObject {
        val state = read(); val row = jsonObjects(state.optJSONArray("receipts")).firstOrNull {
            it.optString("id") == id && it.optString("session") == session
        } ?: throw WorkspaceFailure("execution_not_found", "No execution receipt with that ID exists in this conversation.")
        processes[id]?.let { running ->
            if (running.process.isAlive) return publicReceipt(row).put("status", "running")
                .put("stdout", synchronized(running.stdout) { running.stdout.toString() })
                .put("stderr", synchronized(running.stderr) { running.stderr.toString() })
                .put("outputTruncated", running.truncatedOut || running.truncatedErr)
            runCatching { settle(running, running.process.exitValue()) }
        }
        val latest = jsonObjects(read().optJSONArray("receipts")).firstOrNull { it.optString("id") == id } ?: row
        return publicReceipt(latest).put("status", "ok")
    }

    private fun publicReceipt(row: JSONObject) = JSONObject().put("id", row.getString("id")).put("state", row.getString("state"))
        .put("command", row.getString("command")).put("cwd", row.optString("cwd"))
        .put("startedAt", row.optLong("startedAt")).putOpt("finishedAt", row.opt("finishedAt"))
        .putOpt("exitCode", row.opt("exitCode")).put("stdout", row.optString("stdout"))
        .put("stderr", row.optString("stderr")).put("outputTruncated", row.optBoolean("stdoutTruncated") || row.optBoolean("stderrTruncated"))

    @Synchronized private fun stop(session: String, id: String): JSONObject {
        val running = processes[id]?.takeIf { it.session == session }
            ?: throw WorkspaceFailure("execution_not_running", "This execution is not running in the current conversation.")
        stopTree(running.process)
        if (running.process.waitFor(3, TimeUnit.SECONDS)) settle(running, 143)
        else { running.process.destroyForcibly(); running.process.waitFor(3, TimeUnit.SECONDS); settle(running, 143) }
        return JSONObject().put("status", "stopped").put("id", id)
    }

    fun cancelSession(session: String) {
        processes.values.filter { it.session == session }.forEach { running ->
            stopTree(running.process)
            runCatching {
                if (!running.process.waitFor(3, TimeUnit.SECONDS)) running.process.destroyForcibly()
                running.process.waitFor(3, TimeUnit.SECONDS)
                settle(running, 143)
            }
        }
    }

    fun hasRunning(session: String): Boolean = processes.values.any { it.session == session && it.process.isAlive }

    private fun stopTree(process: Process) {
        runCatching { process.toHandle().descendants().forEach { it.destroy() } }
        process.destroy()
        if (runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) return
        runCatching { process.toHandle().descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    @Synchronized private fun sandboxBackend(): String = when {
        System.getProperty("os.name").equals("Mac OS X", true) && Files.isExecutable(Path.of("/usr/bin/sandbox-exec")) -> "macos-sandbox-exec"
        System.getProperty("os.name").lowercase().contains("linux") && listOf("/usr/bin/bwrap", "/bin/bwrap").any { Files.isExecutable(Path.of(it)) } -> "linux-bwrap"
        else -> "unavailable"
    }

    override fun close() {
        processes.values.toList().forEach { running ->
            stopTree(running.process)
            runCatching { running.process.waitFor(3, TimeUnit.SECONDS); settle(running, 143) }
        }
        processes.clear()
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val OUTPUT_LIMIT = 32 * 1024
        private const val MAX_RUNTIME_MINUTES = 15L
        private val privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        private fun jsonStrings(array: JSONArray?): List<String> = if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }
}
