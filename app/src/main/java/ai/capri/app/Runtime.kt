package ai.capri.app

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/** PRoot changes filesystem presentation. It is not an Android UID security boundary. */
class PythonRuntime(private val context: Context, private val workspace: Workspace) {
    companion object { private val bootstrapLock = Any() }
    private fun prepareBootstrap(): File = synchronized(bootstrapLock) {
        val directory = File(context.filesDir, "runtime/bootstrap-v1").apply { mkdirs() }
        for (name in listOf("session_bootstrap.py", "environment_probe.py", "task_environment.py", "git_credential.py", "ssh_config", "ssh_identity.py", "bin/ssh", "bin/sftp", "bin/scp")) {
            val script = File(directory, name)
            script.parentFile!!.mkdirs()
            val bytes = context.assets.open("runtime/$name").use { it.readBytes() }
            if (!script.exists() || !script.readBytes().contentEquals(bytes)) {
                val target = android.util.AtomicFile(script)
                val output = target.startWrite()
                try { output.write(bytes); target.finishWrite(output) }
                catch (error: Exception) { target.failWrite(output); throw error }
            }
        }
        for (name in listOf("ssh", "sftp", "scp")) check(File(directory, "bin/$name").setExecutable(true, true))
        File(rootfs, "opt/capri/bootstrap").mkdirs()
        directory
    }
    internal val environment = LinuxEnvironment(context)
    private val rootfs get() = environment.root
    private val proot get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    private val loader get() = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    val ready get() = proot.exists() && loader.exists() && environment.ready
    suspend fun install(): String { environment.ensureBase(); return probe() }
    suspend fun probe(): String = execute("runtime-check", "import sys,platform;print('Capri Ubuntu ready');print(sys.version);print(platform.machine())")
    fun status() = if (ready) "执行环境已就绪" else environment.state.value.let { if (it.error.isBlank()) it.message else it.message + "：" + it.error }
    suspend fun ensureSession(id: String) = withContext(Dispatchers.IO) {
        try {
            environment.ensureBase()
            environment.sessionLock(id) {
                val home = workspace.folder(id, "home")
                val marker = File(home, ".capri-session-ready")
                if (!marker.isFile || !java.nio.file.Files.exists(File(home, ".venv/bin/python3").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    environment.chatState(id, LinuxState("preparing", "正在准备会话环境"))
                    val run = workspace.folder(id, "runs").resolve("setup").apply { mkdirs() }
                    val output = File(run, "output.txt")
                    val process = startProcess(id, run, listOf("/bin/true"), output, "会话准备")
                    try {
                        val completed = withTimeoutOrNull(60_000) {
                            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) currentCoroutineContext().ensureActive()
                            true
                        }
                        check(completed == true) { "Session preparation timed out after 60 seconds; retry after checking the state." }
                        check(process.exitValue() == 0) { "Session preparation failed: " + output.readText().takeLast(1500) }
                        marker.writeText("ubuntu-noble-v1")
                    } finally { stop(process) }
                }
            }
            environment.chatState(id, LinuxState("ready", "执行环境已就绪"))
        } catch (cancel: kotlinx.coroutines.CancellationException) { environment.chatState(id, LinuxState("error", "会话准备已中断")); throw cancel }
        catch (error: Exception) { environment.chatState(id, LinuxState("error", "会话环境准备未完成", error.message?.take(1500).orEmpty())); throw error }
    }
    suspend fun execute(id: String, code: String): String = withContext(Dispatchers.IO) {
        ensureSession(id)
        require(code.length <= 128_000) { "Script exceeds 128000 characters." }
        val run = workspace.folder(id, "runs").resolve(java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val script = File(run, "main.py").apply { writeText(code) }
        val output = File(run, "output.txt")
        val process = startProcess(id, run, listOf("/usr/bin/python3", "/run/capri/main.py"), output)
        try {
            val completed = withTimeoutOrNull(60_000) {
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    check(output.length() < 2_000_000) { "Output exceeded 2 MB; execution stopped." }
                }
                true
            }
            check(completed == true) { "Execution timed out after 60 seconds and was stopped." }
            check(output.length() <= 2_000_000) { "Output exceeds 2 MB." }
            "exit=${process.exitValue()}\n" + output.bufferedReader().use { reader ->
                val buffer = CharArray(40_000)
                val count = reader.read(buffer)
                if (count < 0) "" else String(buffer, 0, count)
            }
        } finally { stop(process); script.setReadOnly() }
    }
    internal suspend fun runCommand(id: String, command: List<String>, timeout: Long = 60, exclusive: Boolean = false, resultLimit: Int = 30_000): String = withContext(Dispatchers.IO) {
        ensureSession(id)
        val run = workspace.folder(id, "runs").resolve(java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val output = File(run, "output.txt")
        val launch = { OwnedProcesses.start(processBuilder(id, run, command).redirectErrorStream(true).redirectOutput(output), run) }
        val process = if (exclusive) RuntimeGate.startExclusive(context, id, launch) else RuntimeGate.start(context, id, "环境检查", launch)
        try {
            val completed = withTimeoutOrNull(timeout * 1000) {
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    currentCoroutineContext().ensureActive()
                    check(output.length() < 2_000_000) { "Environment operation output exceeds 2 MB." }
                }
                true
            }
            check(completed == true) { "Environment operation timed out after ${timeout} seconds and was stopped. Partial changes may remain; inspect before retrying." }
            "exit=${process.exitValue()}\n" + output.readText().takeLast(resultLimit)
        } finally { stop(process) }
    }
    private fun stop(process: Process) = OwnedProcesses.stop(process)
    /** MCP uses raw pipes, no PTY, bootstrap, Git broker, or global SSH mounts. */
    internal fun startExtensionProcess(id: String, extension: File, run: File, command: List<String>, tokenEnvironment: Pair<String, String>? = null): Process =
        RuntimeGate.start(context, id, "MCP") {
            check(ready) { status() }
            val home = File(workspace.folder(id, "extensions"), extension.parentFile!!.name + "/home").apply { mkdirs() }
            val tmp = File(home.parentFile, "tmp").apply { mkdirs() }
            val cache = File(context.filesDir, "runtime/package-cache").apply { mkdirs() }
            val args = listOf(proot.path, "--kill-on-exit", "--link2symlink", "-0", "-r", rootfs.path,
                "-b", "/dev", "-b", "/proc", "-b", "${workspace.folder(id, "workspace").path}:/workspace",
                "-b", "${home.path}:/root", "-b", "${tmp.path}:/tmp", "-b", "${extension.path}:/extension",
                "-b", "${cache.path}:/opt/capri/cache", "-w", "/workspace", "/usr/bin/env", "-i",
                "HOME=/root", "TMPDIR=/tmp", "LANG=C.UTF-8", "PYTHONUNBUFFERED=1", "PYTHONNOUSERSITE=1",
                "PATH=/extension/venv/bin:/extension/node_modules/.bin:/usr/local/bin:/usr/bin:/bin",
                "PIP_CACHE_DIR=/opt/capri/cache/pip", "npm_config_cache=/opt/capri/cache/npm",
                "npm_config_update_notifier=false", "npm_config_audit=false", "npm_config_fund=false") +
                listOfNotNull(tokenEnvironment?.let { "${it.first}=${it.second}" }) +
                command.map { it.replace("\${extension}", "/extension").replace("\${workspace}", "/workspace") }
            val builder = ProcessBuilder(args).apply { environment().apply { clear(); put("PROOT_LOADER", loader.path); put("PROOT_TMP_DIR", tmp.path) } }
            OwnedProcesses.start(builder, run)
        }
    /** Shared launch configuration; both short Python and persistent terminals use the same mappings. */
    internal fun startProcess(id: String, run: File, command: List<String>, output: File? = null, kind: String = "脚本"): Process =
        RuntimeGate.start(context, id, kind) {
            val builder = processBuilder(id, run, command)
            if (output != null) builder.redirectErrorStream(true).redirectOutput(output)
            OwnedProcesses.start(builder, run)
        }
    private fun processBuilder(id: String, run: File, command: List<String>): ProcessBuilder {
        check(ready) { status() }
        val bootstrap = prepareBootstrap()
        val gitSocket = ai.capri.app.git.GitCredentialBroker.start(context)
        val ssh = SshEnvironment.prepare(context)
        File(rootfs, "opt/capri/ssh").mkdirs()
        val cache = File(context.filesDir, "runtime/package-cache").apply { mkdirs() }
        File(rootfs, "opt/capri/cache").mkdirs()
        val home = workspace.folder(id, "home")
        val tmp = workspace.folder(id, "tmp")
        val cwd = workspace.folder(id, "workspace")
        val args = listOf(proot.path, "--kill-on-exit", "--link2symlink", "-0", "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "${cwd.path}:/workspace", "-b", "${home.path}:/root",
            "-b", "${tmp.path}:/tmp", "-b", "${run.path}:/run/capri", "-b", "${bootstrap.path}:/opt/capri/bootstrap",
            "-b", "${cache.path}:/opt/capri/cache", "-b", "${ssh.path}:/opt/capri/ssh", "-w", "/workspace",
            "/usr/bin/env", "-i", "HOME=/root", "TMPDIR=/tmp", "PATH=/usr/local/bin:/usr/bin:/bin",
            "LANG=C.UTF-8", "PYTHONUNBUFFERED=1", "PYTHONNOUSERSITE=1", "VIRTUAL_ENV=/root/.venv", "CAPRI_GIT_SOCKET=$gitSocket",
            "/usr/bin/python3", "/opt/capri/bootstrap/session_bootstrap.py") + command
        return ProcessBuilder(args).apply {
            environment().apply { clear(); put("PROOT_LOADER", loader.path); put("PROOT_TMP_DIR", tmp.path) }
        }
    }

}
