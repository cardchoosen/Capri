package ai.capri.app.nativebridge

import android.content.Context
import android.os.Process as AndroidProcess
import android.os.SystemClock
import ai.capri.app.RuntimeFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Optional host su backend. PRoot and the rest of the app keep their original Android UID. */
internal class RootRuntime(private val context: Context) {
    private val prefs = context.getSharedPreferences("root-runtime", Context.MODE_PRIVATE)
    companion object { private val mutex = Mutex() }
    private fun suPath(): String? = (listOf("/system/bin/su", "/system/xbin/su", "/system_ext/bin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su") +
        System.getenv("PATH").orEmpty().split(':').filter { it.startsWith('/') }.map { "$it/su" })
        .distinct().firstOrNull { File(it).isFile && File(it).canExecute() }

    fun snapshot(): JSONObject {
        val su = suPath()
        val state = prefs.getString("authorization", "not_checked")!!
        return JSONObject().put("backend_implemented", true).put("execution_environment", "Android host, outside PRoot")
            .put("su_detected", su != null).put("su_path", su ?: JSONObject.NULL)
            .put("device_root", if (su == null) "not_detected" else if (prefs.getBoolean("root_verified", false)) "confirmed" else "su_detected")
            .put("app_uid", AndroidProcess.myUid()).put("app_authorization", state)
            .put("last_checked_at", prefs.getLong("checked_at", 0)).put("last_verified_root_uid", if (prefs.getBoolean("root_verified", false)) 0 else JSONObject.NULL)
            .put("current_command_verified", false).put("guest_uid0_is_host_root", false)
            .put("last_cleanup", prefs.getString("last_cleanup", "not_run")).put("last_cleanup_at", prefs.getLong("last_cleanup_at", 0))
            .put("note", "状态探测不弹授权。su存在是线索；confirmed与授权状态来自此前实测，不是永久权限保证。root_request或root_exec会重新核验本次UID；拒绝不等于设备未root。")
    }

    private fun authorization(state: String, verified: Boolean = false) {
        prefs.edit().putString("authorization", state).putLong("checked_at", System.currentTimeMillis()).apply {
            if (verified) putBoolean("root_verified", true)
        }.apply()
    }
    suspend fun requestAuthorization(): JSONObject = runCommand(RootRequest("/system/bin/id\n/system/bin/getenforce\n", 5, 4000), context.filesDir, request = true)
    suspend fun execute(params: JSONObject, workspace: File?): JSONObject {
        val request = RootProtocol.request(params)
        if (prefs.getString("authorization", "not_checked") != "granted") throw NativeFailure("permission_required", "先调用root_request，通过手机Root管理器授权并验证Capri的真实root身份。", snapshot().put("next_action", "root_request"))
        return runCommand(request, workspace ?: context.filesDir, request = false)
    }

    private fun builder(command: List<String>, directory: File, log: File): ProcessBuilder = ProcessBuilder(command)
        .directory(directory).redirectErrorStream(true).redirectOutput(log).apply {
            environment().apply {
                remove("LD_PRELOAD"); remove("PROOT_LOADER"); remove("PROOT_TMP_DIR")
                put("PATH", "/system/bin:/system/xbin:/system_ext/bin:/vendor/bin")
            }
        }
    private fun read(file: File, limit: Int): String = file.inputStream().use { input ->
        val bytes = ByteArray(limit); var total = 0
        while (total < limit) { val count = input.read(bytes, total, limit - total); if (count < 0) break; total += count }
        String(bytes, 0, total, Charsets.UTF_8)
    }
    private fun identity(directory: File): String? = runCatching {
        read(File(directory, "identity"), 120).trim().takeIf { it.matches(Regex("[1-9][0-9]* [0-9]+ 0")) }
    }.getOrNull()

    private suspend fun cleanup(su: String, directory: File): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        if (identity(directory) == null) return@withContext false
        val process = runCatching { builder(listOf(su, "-c", RootProtocol.cleanup(directory)), directory, File(directory, "cleanup.log")).start() }.getOrNull()
            ?: return@withContext false
        try {
            withTimeoutOrNull(4000) { while (process.isAlive) delay(25); process.exitValue() == 0 } ?: false
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private suspend fun runCommand(spec: RootRequest, cwd: File, request: Boolean): JSONObject = withContext(Dispatchers.IO) {
        val su = suPath() ?: throw NativeFailure("unsupported", "未发现可执行su；不能据此绝对断言设备未root。", snapshot())
        if (!File("/system/bin/setsid").canExecute()) throw NativeFailure("unsupported", "设备缺少setsid，无法建立可管理的root进程组。")
        if (!mutex.tryLock()) throw NativeFailure("busy", "已有root操作或授权正在进行，请等待完成。")
        var directory: File? = null
        var process: Process? = null
        var cleanupVerified = false
        var dispatched = false
        var outcome: JSONObject? = null
        try {
            directory = File(context.cacheDir, "root-runs/${UUID.randomUUID()}").apply { check(mkdirs()) }
            listOf("identity", "release", "done", "stdout", "stderr").forEach { File(directory, it).writeText("") }
            File(directory, "command.sh").writeText(spec.command + "\n")
            // Includes the authorization handshake; the app imposes a shorter command-only deadline.
            File(directory, "supervisor.sh").writeText(RootProtocol.supervisor(directory, cwd, spec.timeoutSeconds + 40))
            process = builder(RootProtocol.launch(su, directory), directory, File(directory, "launcher.log")).start()
            process.outputStream.close()
            val authDeadline = SystemClock.elapsedRealtime() + if (request) 30_000 else 5000
            while (identity(directory) == null && process.isAlive && SystemClock.elapsedRealtime() < authDeadline) {
                if (File(directory, "launcher.log").length() > 64000) break
                delay(40)
            }
            if (identity(directory) == null) {
                val timedOut = process.isAlive
                val diagnostic = read(File(directory, "launcher.log"), 2000)
                val denied = diagnostic.contains("denied", ignoreCase = true) || diagnostic.contains("not allowed", ignoreCase = true)
                authorization(if (timedOut) "timeout" else if (denied) "denied" else "verification_failed")
                throw NativeFailure(if (timedOut) "timeout" else if (denied) "permission_denied" else "verification_failed",
                    if (timedOut) "Root授权或监督进程核验尚未完成，用户命令未启动；检查手机Root管理器提示。" else "su未返回可验证的root监督进程身份；检查管理器授权及launcher_exit_code。不能据此断言设备未root。",
                    snapshot().put("command_dispatched", false).put("launcher_exit_code", if (process.isAlive) JSONObject.NULL else process.exitValue()).put("diagnostic", diagnostic))
            }
            authorization("granted", verified = true)
            val deadline = SystemClock.elapsedRealtime() + spec.timeoutSeconds * 1000L
            File(directory, "release").writeText("go\n"); dispatched = true
            var reason = "completed"
            while (File(directory, "done").length() == 0L) {
                if (!process.isAlive) { reason = "interrupted"; break }
                if (File(directory, "stdout").length() + File(directory, "stderr").length() > spec.outputLimit) { reason = "output_limit"; break }
                if (SystemClock.elapsedRealtime() >= deadline) { reason = "timeout"; break }
                delay(25)
            }
            cleanupVerified = cleanup(su, directory)
            val exit = File(directory, "done").readText().trim().toIntOrNull()
            val out = read(File(directory, "stdout"), spec.outputLimit)
            val err = read(File(directory, "stderr"), (spec.outputLimit - out.toByteArray().size).coerceAtLeast(0))
            val (safeOut, safeErr) = RootProtocol.boundedOutput(out, err)
            val truncated = File(directory, "stdout").length() + File(directory, "stderr").length() > spec.outputLimit || safeOut.length < out.length || safeErr.length < err.length
            if (truncated && reason == "completed") reason = "output_limit"
            val executionStatus = when { reason != "completed" -> reason; exit == null -> "interrupted"; exit != 0 -> "command_failed"; else -> "ok" }
            val status = if (cleanupVerified) executionStatus else "cleanup_unconfirmed"
            outcome = JSONObject().put("status", status).put("data", snapshot().put("current_command_verified", true).put("effective_uid", 0)
                .put("command_dispatched", true).put("exit_code", exit ?: JSONObject.NULL).put("stdout", safeOut).put("stderr", safeErr)
                .put("output_truncated", truncated).put("reason", reason).put("cleanup_verified", cleanupVerified)
                .put("working_directory", cwd.path).put("timeout_seconds", spec.timeoutSeconds)
                .put("notice", "宿主Android root命令，不是Ubuntu/PRoot；非零退出码、超时或输出上限不能当作成功。状态不明不要直接重跑有副作用操作。"))
            outcome!!
        } finally {
            if (directory != null) {
                if (!cleanupVerified) cleanupVerified = cleanup(su, directory)
                if (outcome != null) outcome!!.getJSONObject("data").put("cleanup_verified", cleanupVerified).also {
                    if (!cleanupVerified) outcome!!.put("status", "cleanup_unconfirmed")
                    else if (outcome!!.optString("status") == "cleanup_unconfirmed") {
                        val data = outcome!!.getJSONObject("data")
                        outcome!!.put("status", when { data.getString("reason") != "completed" -> data.getString("reason"); data.isNull("exit_code") -> "interrupted"; data.getInt("exit_code") != 0 -> "command_failed"; else -> "ok" })
                    }
                }
                if (dispatched) prefs.edit().putString("last_cleanup", if (cleanupVerified) "verified" else "unconfirmed").putLong("last_cleanup_at", System.currentTimeMillis()).apply()
                if (process?.isAlive == true) process.destroyForcibly()
                // Keep only bounded status metadata; no scripts, outputs or credentials in a permanent root log.
                if (cleanupVerified || !dispatched) runCatching { RuntimeFiles.delete(directory) }
            }
            mutex.unlock()
        }
    }
}
