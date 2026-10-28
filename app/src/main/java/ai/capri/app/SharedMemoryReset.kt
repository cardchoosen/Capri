package ai.capri.app

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** A durable intent prevents a process death halfway through reset from resurrecting old memories. */
internal object SharedMemoryReset {
    const val DESCRIPTION = "清空你与 Capri 的全部对话消息（含任务对话）、个人记忆、手动记忆、资料索引、历史摘要、恢复检查点与网页检索缓存。\n\n保留核心人格、安装环境档案、任务文件、执行环境、浏览器登录和账号配置。已导出或写入任务文件的内容不会被删除，之后主动读取文件仍可能看到它们。\n\n此操作不可撤销；普通升级不会触发重置。"
    private fun marker(context: Context) = AtomicFile(File(context.filesDir, "shared-memory-reset.pending"))
    fun pending(context: Context) = File(context.filesDir, "shared-memory-reset.pending").exists() || File(context.filesDir, "shared-memory-reset.pending.bak").exists()
    fun begin(context: Context) {
        val file = marker(context); val stream = file.startWrite()
        try { stream.write("reset-v1".toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun resume(context: Context, store: Store) {
        if (!pending(context)) return
        clearCaches(context.filesDir)
        val sessions = store.load().map { it.copy(title = if (it.kind == SessionKind.PERSONAL) "Capri" else "新会话", messages = mutableListOf()) }
        store.save(sessions)
        marker(context).delete()
        check(!pending(context)) { "重置标记未能清除，请重试" }
    }
    internal fun clearCaches(root: File) {
        listOf("knowledge", "context-history", "agent-recovery", "web-retrieval").forEach { RuntimeFiles.delete(File(root, it)) }
    }
}
