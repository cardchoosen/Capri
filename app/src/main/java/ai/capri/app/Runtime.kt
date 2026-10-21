package ai.capri.app

import android.content.Context
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit

/** PRoot changes filesystem presentation. It is not an Android UID security boundary. */
class PythonRuntime(private val context: Context, private val workspace: Workspace) {
    private val rootfs get() = File(context.filesDir, "runtime/rootfs")
    private val proot get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    private val loader get() = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    val ready get() = proot.exists() && loader.exists() && File(rootfs, "usr/bin/python3").exists()
    suspend fun install(): String = withContext(Dispatchers.IO) {
        if (ready) return@withContext probe()
        check(proot.exists() && loader.exists()) { "APK缺少ARM64原生执行组件" }
        val archive = File(context.cacheDir, "rootfs.tar.gz")
        context.assets.open("runtime/rootfs.bundle").use { input -> archive.outputStream().use { input.copyTo(it) } }
        rootfs.mkdirs()
        val log = File(context.cacheDir, "runtime-install.log")
        val process = ProcessBuilder("/system/bin/tar", "-xzf", archive.path, "-C", rootfs.path).redirectErrorStream(true).redirectOutput(log).start()
        try {
            withTimeout(120_000) { while (!process.waitFor(100, TimeUnit.MILLISECONDS)) kotlin.coroutines.coroutineContext.ensureActive() }
            check(process.exitValue() == 0) { "环境解包失败：" + log.readText().take(2000) }
            check(ready) { "环境文件不完整" }
        } finally { process.destroyForcibly(); archive.delete() }
        probe()
    }
    suspend fun probe(): String = execute("runtime-check", "import sys, pathlib, platform\nprint('Capri runtime ready')\nprint(sys.version)\nprint(platform.machine())\nprint('workspace:', pathlib.Path.cwd())")
    fun status() = if (ready) "PRoot / Python 已安装" else "PRoot / Python 尚未安装。会话与文件功能可用；执行工具将返回真实的未就绪状态。"
    suspend fun execute(id: String, code: String): String = withContext(Dispatchers.IO) {
        check(ready) { status() }
        require(code.length <= 128_000) { "脚本过大" }
        val run = workspace.folder(id, "runs").resolve(java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val script = File(run, "main.py").apply { writeText(code) }
        val output = File(run, "output.txt")
        File(run, "bootstrap.py").writeText("""
            import os, pathlib, venv
            env = pathlib.Path('/root/.venv')
            if not (env / 'bin/python3').exists():
                venv.EnvBuilder(with_pip=False, symlinks=True).create(str(env))
            os.execv(str(env / 'bin/python3'), [str(env / 'bin/python3'), '/run/capri/main.py'])
        """.trimIndent())
        val builder = processBuilder(id, run, listOf("/usr/bin/python3", "/run/capri/bootstrap.py"))
        val process = builder.redirectErrorStream(true).redirectOutput(output).start()
        try {
            val completed = withTimeoutOrNull(60_000) {
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    check(output.length() < 2_000_000) { "输出超过 2 MB，已停止" }
                }
                true
            }
            check(completed == true) { "执行超时（60秒），已停止" }
            check(output.length() <= 2_000_000) { "输出超过 2 MB" }
            "exit=${process.exitValue()}\n" + output.bufferedReader().use { reader ->
                val buffer = CharArray(40_000)
                val count = reader.read(buffer)
                if (count < 0) "" else String(buffer, 0, count)
            }
        } finally { process.destroyForcibly(); script.setReadOnly() }
    }
    /** Shared launch configuration; both short Python and persistent terminals use the same mappings. */
    internal fun processBuilder(id: String, run: File, command: List<String>): ProcessBuilder {
        check(ready) { status() }
        val home = workspace.folder(id, "home")
        val tmp = workspace.folder(id, "tmp")
        val cwd = workspace.folder(id, "workspace")
        val args = listOf(proot.path, "--kill-on-exit", "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "${cwd.path}:/workspace", "-b", "${home.path}:/root",
            "-b", "${tmp.path}:/tmp", "-b", "${run.path}:/run/capri", "-w", "/workspace",
            "/usr/bin/env", "-i", "HOME=/root", "TMPDIR=/tmp", "PATH=/usr/local/bin:/usr/bin:/bin",
            "LANG=C.UTF-8", "PYTHONUNBUFFERED=1") + command
        return ProcessBuilder(args).apply {
            environment().apply { clear(); put("PROOT_LOADER", loader.path); put("PROOT_TMP_DIR", tmp.path) }
        }
    }

}
