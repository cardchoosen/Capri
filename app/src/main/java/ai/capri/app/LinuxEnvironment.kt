package ai.capri.app

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class LinuxState(val phase: String = "pending", val message: String = "正在准备执行环境", val error: String = "")

/** One real Ubuntu package database; no custom Python/Node/compiler component layers. */
internal class LinuxEnvironment(private val context: Context) {
    private class Shared {
        val install = Mutex()
        val retired = ConcurrentHashMap.newKeySet<String>()
        val sessions = ConcurrentHashMap<String, Mutex>()
        val state = MutableStateFlow(LinuxState())
        val chats = MutableStateFlow<Map<String, LinuxState>>(emptyMap())
    }
    companion object { private val shared = ConcurrentHashMap<String, Shared>() }
    private val sharedState = shared.getOrPut(context.filesDir.canonicalPath) { Shared() }
    val state = sharedState.state.asStateFlow()
    val chats = sharedState.chats.asStateFlow()
    val root = File(context.filesDir, "runtime/ubuntu")
    private val source get() = context.assets.open("runtime/ubuntu-source.json").bufferedReader().use { JSONObject(it.readText()) }
    val ready get() = File(root, ".capri-ready").let { it.isFile && it.readText().trim() == source.getString("sha256") } &&
        File(root, "usr/bin/python3").exists() && File(root, "var/lib/dpkg/status").isFile
    fun publish(phase: String, message: String, error: String = "") { sharedState.state.value = LinuxState(phase, message, error) }
    fun chatState(chat: String, value: LinuxState) = synchronized(sharedState) {
        if (chat !in sharedState.retired) sharedState.chats.value = sharedState.chats.value + (chat to value)
    }
    fun retireChat(chat: String) = synchronized(sharedState) { sharedState.retired.add(chat); sharedState.chats.value = sharedState.chats.value - chat; Unit }
    fun restoreChat(chat: String) { sharedState.retired.remove(chat) }
    suspend fun <T> sessionLock(chat: String, block: suspend () -> T): T = sharedState.sessions.getOrPut(chat) { Mutex() }.withLock {
        check(chat !in sharedState.retired) { "会话已删除" }; block()
    }
    suspend fun ensureBase() = withContext(Dispatchers.IO) {
        sharedState.install.withLock {
            if (ready) { publish("ready", "执行环境已就绪"); return@withLock }
            try {
                val spec = source
                val archive = File(context.cacheDir, spec.getString("filename"))
                val staging = File(root.parentFile, "ubuntu.staging")
                val previous = File(root.parentFile, "ubuntu.previous")
                root.parentFile!!.mkdirs()
                check(!Files.isSymbolicLink(root.parentFile!!.toPath()) && !Files.isSymbolicLink(root.toPath())) { "执行环境目录异常" }
                if (!root.exists() && previous.exists()) check(previous.renameTo(root))
                if (File(root, ".capri-source").readOrEmpty() != spec.getString("sha256")) {
                    publish("preparing", "正在下载执行环境")
                    download(archive, spec)
                    RuntimeFiles.delete(staging); check(staging.mkdirs())
                    publish("preparing", "正在解包执行环境")
                    runHost(listOf("/system/bin/tar", "-xzf", archive.path, "--exclude=usr/bin/perl5.38.2", "--exclude=usr/bin/uncompress", "-C", staging.path), 120, "解包")
                    Files.createSymbolicLink(File(staging, "usr/bin/perl5.38.2").toPath(), java.nio.file.Paths.get("perl"))
                    Files.createSymbolicLink(File(staging, "usr/bin/uncompress").toPath(), java.nio.file.Paths.get("gunzip"))
                    File(staging, ".capri-source").writeText(spec.getString("sha256"))
                    // link2symlink stores absolute host paths. Package installation MUST use the final path.
                    // No runtime can start until the health-verified ready marker has been committed below.
                    RuntimeGate.mutate(context) {
                        RuntimeFiles.delete(previous)
                        if (root.exists()) check(root.renameTo(previous)) { "无法切换执行环境" }
                        if (!staging.renameTo(root)) { if (previous.exists()) previous.renameTo(root); error("执行环境切换失败") }
                    }
                }
                for (name in listOf("tmp", "root", "workspace", "run/capri", "opt/capri/bootstrap", "opt/capri/ssh")) File(root, name).mkdirs()
                File(root, "etc/resolv.conf").let { file -> Files.deleteIfExists(file.toPath()); file.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
                val setup = File(root, "root/capri-setup.sh")
                context.assets.open("runtime/ubuntu_setup.sh").use { input -> setup.outputStream().use { input.copyTo(it) } }
                publish("preparing", "正在准备基础执行能力，稍后会自动继续")
                runGuest(root, listOf("/bin/sh", "/root/capri-setup.sh"), 1200, "基础环境安装")
                runGuest(root, listOf("/bin/sh", "-c", "python3 -m venv /tmp/capri-health && /tmp/capri-health/bin/python -c 'import ssl,sqlite3,pip;print(\"verified\")' && rm -rf /tmp/capri-health && git --version && ssh -V"), 60, "基础环境验证")
                val marker = android.util.AtomicFile(File(root, ".capri-ready"))
                val output = marker.startWrite()
                try { output.write(spec.getString("sha256").toByteArray()); marker.finishWrite(output) }
                catch (error: Exception) { marker.failWrite(output); throw error }
                RuntimeFiles.delete(previous)
                archive.delete()
                publish("ready", "执行环境已就绪")
            } catch (cancel: CancellationException) { publish("error", "环境准备已中断，可重试"); throw cancel }
            catch (error: Exception) { publish("error", "执行环境准备未完成", error.message?.take(1200).orEmpty()); throw error }
        }
    }
    private suspend fun download(file: File, spec: JSONObject) {
        if (file.isFile && file.length() == spec.getLong("size") && RuntimeFiles.sha(file) == spec.getString("sha256")) return
        val pending = File(file.parentFile, file.name + ".download")
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES).build()
        try {
            val url = spec.getString("url"); require(url.startsWith("https://cdimage.ubuntu.com/"))
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "环境下载HTTP ${response.code}" }
                val body = response.body ?: error("环境下载为空")
                body.byteStream().use { input -> pending.outputStream().use { output ->
                    val buffer = ByteArray(65536); var count = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(buffer); if (size < 0) break
                        count += size; check(count <= spec.getLong("size")) { "环境下载大小异常" }; output.write(buffer, 0, size)
                    }
                } }
            }
            check(pending.length() == spec.getLong("size") && RuntimeFiles.sha(pending) == spec.getString("sha256")) { "执行环境下载校验失败" }
            check(pending.renameTo(file)) { "无法保存已校验的执行环境" }
        } finally { pending.delete(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    private suspend fun runHost(args: List<String>, timeout: Long, phase: String) {
        val log = File(context.cacheDir, "ubuntu-setup.log")
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
        waitFor(process, log, timeout, phase)
    }
    private suspend fun runGuest(directory: File, command: List<String>, timeout: Long, phase: String) {
        val tmp = File(context.cacheDir, "ubuntu-proot-tmp").apply { mkdirs() }
        val libraries = context.applicationInfo.nativeLibraryDir
        val args = listOf("$libraries/libproot.so", "--kill-on-exit", "--link2symlink", "-0", "-r", directory.path,
            "-b", "/dev", "-b", "/proc", "-w", "/", "/usr/bin/env", "-i", "HOME=/root", "TMPDIR=/tmp",
            "PATH=/usr/sbin:/usr/bin:/sbin:/bin", "LANG=C.UTF-8", "DEBIAN_FRONTEND=noninteractive") + command
        val log = File(context.cacheDir, "ubuntu-setup.log")
        val process = ProcessBuilder(args).apply {
            environment().apply { clear(); put("PROOT_LOADER", "$libraries/libproot-loader.so"); put("PROOT_TMP_DIR", tmp.path) }
        }.redirectErrorStream(true).redirectOutput(log).start()
        waitFor(process, log, timeout, phase)
    }
    private suspend fun waitFor(process: Process, log: File, timeout: Long, phase: String) {
        try {
            val completed = withTimeoutOrNull(timeout * 1000) {
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) currentCoroutineContext().ensureActive()
                true
            }
            check(completed == true) { "$phase 超时，可重试恢复" }
            check(process.exitValue() == 0) { "$phase 未完成：" + log.readOrEmpty().takeLast(2500) }
        } finally { process.destroyForcibly() }
    }
    private fun File.readOrEmpty() = if (isFile) readText().trim() else ""
}
