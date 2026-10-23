package ai.capri.app

import android.content.Context

/** All app-owned PRoot starts and component mutations share this gate. No coroutine suspension inside it. */
internal object RuntimeGate {
    data class Use(val chat: String, val kind: String)
    private data class Running(val root: String, val process: Process, val use: Use)
    private val running = mutableListOf<Running>()
    private fun root(context: Context) = context.filesDir.canonicalPath
    private fun prune() { running.removeAll { !it.process.isAlive } }
    @Synchronized fun active(context: Context): List<Use> {
        prune(); return running.filter { it.root == root(context) }.map { it.use }
    }
    @Synchronized fun <T> observe(block: () -> T): T = block()
    @Synchronized fun <T> mutate(context: Context, block: () -> T): T {
        check(active(context).isEmpty()) { "有终端或脚本正在使用环境；请先关闭全部持续终端并等待脚本结束，再执行更新或修复。" }
        return block()
    }
    @Synchronized fun start(context: Context, chat: String, kind: String, block: () -> Process): Process {
        prune()
        return block().also { running += Running(root(context), it, Use(chat, kind)) }
    }
}
