package ai.capri.app

import android.os.Process as AndroidProcess
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Captures only a trusted launcher PID, never a PID supplied by a model, guest script or user. */
internal object OwnedProcesses {
    private data class Identity(val pid: Int, val parent: Int, val started: String)
    private val owned = ConcurrentHashMap<Process, Identity>()
    private fun identity(pid: Int): Identity? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val fields = stat.substring(stat.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))
        val uid = File("/proc/$pid/status").useLines { lines -> lines.first { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+")).first().toInt() }
        check(uid == AndroidProcess.myUid())
        Identity(pid, fields[1].toInt(), fields[19])
    }.getOrNull()
    fun start(builder: ProcessBuilder, directory: File): Process {
        owned.entries.removeAll { !it.key.isAlive }
        val pidFile = File(directory, ".capri-launch-${java.util.UUID.randomUUID()}.pid")
        val command = builder.command().toList()
        // All untrusted guest arguments remain argv values; none are interpolated into this host script.
        builder.command(listOf("/system/bin/sh", "-c", "umask 077; printf '%s' \"$$\" > \"$1\"; IFS= read -r launch || exit 125; [ \"${'$'}launch\" = capri-start ] || exit 125; shift; exec \"$@\"", "capri-launch", pidFile.path) + command)
        val process = builder.start()
        try {
            repeat(200) {
                val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
                val candidate = pid?.takeIf { it > 0 }?.let(::identity)
                if (candidate != null && candidate.parent == AndroidProcess.myPid()) {
                    owned[process] = candidate
                    // The guest has not started yet; release only this native-owned launcher's stdin handshake.
                    process.outputStream.write("capri-start\n".toByteArray()); process.outputStream.flush()
                    return process
                }
                if (!process.isAlive) return process
                Thread.sleep(5)
            }
            process.destroyForcibly()
            error("无法确认本次执行进程的归属，未继续任务")
        } finally { pidFile.delete() }
    }
    fun stop(process: Process) {
        val captured = owned[process]
        if (process.isAlive && captured != null) {
            val current = identity(captured.pid)
            if (current == captured && current.parent == AndroidProcess.myPid()) AndroidProcess.killProcess(captured.pid)
        }
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
        if (!process.isAlive) owned.remove(process)
    }
}
