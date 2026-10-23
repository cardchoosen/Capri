package ai.capri.app

import android.content.Context
import java.io.File

/** Explicit one-time user-authorized reset. Global SSH, preferences and persona are outside this allowlist. */
internal object LegacySessionReset {
    private val paths = listOf("sessions", "sessions.json", "sessions.json.bak", "sessions.json.new",
        "terminals.json", "terminals.json.bak", "terminals.json.new", "agent-recovery",
        "runtime/rootfs", "runtime/tools", "runtime/python-packages", "runtime/bootstrap-v1")
    fun marker(context: Context) = File(context.filesDir, "runtime/ubuntu-session-reset-v1.done")
    fun clear(context: Context) {
        check(LinuxEnvironment(context).ready) { "新执行环境验证前不清理旧会话" }
        paths.forEach { RuntimeFiles.delete(File(context.filesDir, it)) }
    }
}
