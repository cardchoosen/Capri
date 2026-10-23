package ai.capri.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/** Explicit allowlists only. Workspace, credentials and the shared package database are never targets. */
internal class EnvironmentMaintenance(private val context: Context, private val workspace: Workspace, private val runtime: PythonRuntime) {
    enum class Action(val label: String, val scope: String) {
        TEMP("清理当前会话临时文件", "删除当前会话 tmp、runs 中的临时文件、脚本副本和执行日志。保留聊天、工作区、性能记录与依赖。"),
        CACHE("清理共享下载缓存", "删除所有会话共享的 pip/npm 下载缓存及 apt 的 .deb 下载缓存。保留已安装依赖和 apt 软件包索引；以后安装可能需要重新下载。"),
        RESET("重置当前会话依赖", "删除当前会话 Python .venv、托管 npm 安装（HOME/.local/npm）及环境引导标记，然后离线重建 Python 基础环境。保留工作区（含项目 node_modules）、HOME 其他文件、聊天、全局 Ubuntu、SSH/Git 及其他会话。")
    }
    // Check every ancestor: a redirected HOME or cache parent must never redirect maintenance outside its scope.
    private fun checked(relative: String): File {
        val base = context.filesDir.absoluteFile
        var current = base
        for (part in relative.split('/')) {
            require(part.isNotBlank() && part != "." && part != "..")
            current = File(current, part)
            check(!Files.isSymbolicLink(current.toPath())) { "维护目录存在符号链接，已停止：$relative" }
        }
        return current
    }
    private fun targets(chat: String, action: Action): List<File> {
        require(chat.matches(Regex("[a-zA-Z0-9-]+")))
        return when (action) {
            Action.TEMP -> listOf("sessions/$chat/tmp", "sessions/$chat/runs").map(::checked)
            Action.CACHE -> {
                val archives = checked("runtime/ubuntu/var/cache/apt/archives")
                listOf(checked("runtime/package-cache/pip"), checked("runtime/package-cache/npm")) +
                    archives.listFiles().orEmpty().filter { it.name.endsWith(".deb") && !Files.isSymbolicLink(it.toPath()) } +
                    checked("runtime/ubuntu/var/cache/apt/archives/partial")
            }
            Action.RESET -> listOf(".venv", ".local/npm", ".capri-session-ready", ".capri-environment.json", ".capri-environment.tmp", ".capri-pip-seeding", ".capri-bootstrap.log")
                .map { checked("sessions/$chat/home/$it") }
        }
    }
    suspend fun estimate(chat: String, action: Action): Long = withContext(Dispatchers.IO) {
        targets(chat, action).sumOf { RuntimeFiles.size(it) }
    }
    suspend fun perform(chat: String, action: Action): String = withContext(Dispatchers.IO) {
        RuntimeGate.beginMaintenance(context, chat, action == Action.RESET)
        try {
            var before = 0L
            var after = 0L
            runtime.environment.sessionLock(chat) {
                // Resolve all paths before any deletion. Never partially delete on a path validation error.
                val files = targets(chat, action)
                before = files.sumOf { RuntimeFiles.size(it) }
                if (action == Action.RESET) runtime.environment.chatState(chat, LinuxState("preparing", "正在重建会话依赖"))
                files.forEach { RuntimeFiles.delete(it) }
                after = files.sumOf { RuntimeFiles.size(it) }
            }
            if (action == Action.RESET) runtime.ensureSession(chat)
            "${action.label}完成；已删除约 ${formatBytes((before - after).coerceAtLeast(0))} 文件内容。" +
                if (action == Action.RESET) "基础 Python 已重建；任务依赖将按需重新安装。" else ""
        } catch (error: Exception) {
            if (action == Action.RESET) runtime.environment.chatState(chat, LinuxState("error", "会话依赖重建未完成，可重试准备", error.message.orEmpty().take(500)))
            throw error
        } finally { RuntimeGate.endMaintenance(context) }
    }
    companion object {
        fun formatBytes(bytes: Long): String = if (bytes < 1024 * 1024) "${bytes / 1024} KiB" else String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
    }
}
