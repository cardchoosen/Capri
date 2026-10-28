package ai.capri.app

import android.content.Context

/** All app-owned PRoot starts and component mutations share this gate. No coroutine suspension inside it. */
internal object RuntimeGate {
    data class Use(val chat: String, val kind: String)
    private data class Running(val root: String, val process: Process, val use: Use)
    private val fileUses = mutableMapOf<String, Pair<String,Use>>()
    @Synchronized fun beginFiles(context: Context, chat: String, kind: String): String {
        check(!maintenance.containsKey(root(context))) { "环境维护中，请稍后操作文件" }
        check((root(context) to chat) !in deletedChats) { "会话已删除" }
        val id=java.util.UUID.randomUUID().toString(); fileUses[id]=root(context) to Use(chat,kind); return id
    }
    @Synchronized fun endFiles(id: String) { fileUses.remove(id) }
    private val deletedChats = mutableSetOf<Pair<String, String>>()
    private val maintenance = mutableMapOf<String, String>()
    private val running = mutableListOf<Running>()
    private fun root(context: Context) = context.filesDir.canonicalPath
    private fun prune() { running.removeAll { !it.process.isAlive } }
    @Synchronized fun active(context: Context): List<Use> {
        prune(); return running.filter { it.root == root(context) }.map { it.use } + fileUses.values.filter { it.first == root(context) }.map { it.second }
    }
    @Synchronized fun <T> observe(block: () -> T): T = block()
    @Synchronized fun beginMaintenance(context: Context, chat: String, allowPreparation: Boolean = false) {
        check(!maintenance.containsKey(root(context))) { "已有环境维护正在进行" }
        check(active(context).isEmpty()) { "请先等待脚本和浏览器文件操作结束，并关闭全部持续终端，再维护环境" }
        maintenance[root(context)] = if (allowPreparation) chat else ""
    }
    @Synchronized fun endMaintenance(context: Context) { maintenance.remove(root(context)) }
    @Synchronized fun beginChatDeletion(context: Context, chat: String) {
        check(!maintenance.containsKey(root(context))) { "环境维护中，请稍后删除会话" }
        check(active(context).none { it.chat == chat }) { "会话仍有脚本或文件操作，请等待结束后删除" }
        deletedChats.add(root(context) to chat)
    }
    @Synchronized fun restoreChat(context: Context, chat: String) { deletedChats.remove(root(context) to chat) }
    private fun checkStart(context: Context, chat: String, kind: String) {
        check((root(context) to chat) !in deletedChats) { "会话已删除" }
        val owner = maintenance[root(context)]
        check(owner == null || (owner == chat && kind == "会话准备")) { "环境维护中，请稍后再执行任务" }
    }
    @Synchronized fun <T> mutate(context: Context, block: () -> T): T {
        check(!maintenance.containsKey(root(context))) { "环境维护中" }
        check(active(context).isEmpty()) { "有终端或脚本正在使用环境；请先关闭全部持续终端并等待脚本结束，再执行更新或修复。" }
        return block()
    }
    @Synchronized fun start(context: Context, chat: String, kind: String, block: () -> Process): Process {
        prune()
        checkStart(context, chat, kind)
        check(running.none { it.root == root(context) && it.use.kind == "系统依赖" }) { "共享环境正在准备依赖，请等待已有操作完成" }
        return block().also { running += Running(root(context), it, Use(chat, kind)) }
    }
    @Synchronized fun startExclusive(context: Context, chat: String, block: () -> Process): Process {
        checkStart(context, chat, "系统依赖")
        check(active(context).isEmpty()) { "共享环境正在被终端或脚本使用，请先结束或关闭相关任务；不会自动中断它们" }
        return block().also { running += Running(root(context), it, Use(chat, "系统依赖")) }
    }

}
