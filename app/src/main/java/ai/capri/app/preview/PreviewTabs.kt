package ai.capri.app.preview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PreviewKind { FILE, WEB, WEB_HOME }
internal data class PreviewTab(val id: String, val title: String, val kind: PreviewKind, val target: String)
/** Selection changes only on explicit UI intent or closing the selected preview. */
internal class PreviewTabs {
    companion object { const val CHAT = "chat"; const val HOME = "web-home"; fun web(id: String) = "web:$id" }
    private class Session { val tabs = mutableListOf<PreviewTab>(); var selected = CHAT; val positions = mutableMapOf<String, Int>() }
    private val sessions = mutableMapOf<String, Session>()
    private val revision = MutableStateFlow(0)
    val updates = revision.asStateFlow()
    private fun session(chat: String) = sessions.getOrPut(chat) { Session() }
    private fun changed() { revision.value++ }
    fun tabs(chat: String) = session(chat).tabs.toList()
    fun selected(chat: String) = session(chat).selected
    fun current(chat: String) = tabs(chat).firstOrNull { it.id == selected(chat) }
    fun select(chat: String, id: String) {
        val s = session(chat)
        require(id == CHAT || s.tabs.any { it.id == id })
        if (s.selected != id) { s.selected = id; changed() }
    }
    fun openFile(chat: String, path: String) {
        require(path.isNotBlank())
        val s = session(chat); val id = "file:$path"
        if (s.tabs.none { it.id == id }) s.tabs += PreviewTab(id, path.substringAfterLast('/'), PreviewKind.FILE, path)
        s.selected = id; changed()
    }
    fun openBrowserHome(chat: String) {
        val s = session(chat)
        if (s.tabs.none { it.id == HOME }) s.tabs += PreviewTab(HOME, "浏览器", PreviewKind.WEB_HOME, "")
        s.selected = HOME; changed()
    }
    fun syncBrowser(chat: String, pages: List<Pair<String, String>>) {
        val s = session(chat); val before = s.tabs.toList()
        val ids = pages.map { web(it.first) }.toSet()
        s.tabs.removeAll { it.kind == PreviewKind.WEB && it.id !in ids }
        pages.forEach { (id, title) ->
            val tab = PreviewTab(web(id), title.ifBlank { "网页" }, PreviewKind.WEB, id)
            val index = s.tabs.indexOfFirst { it.id == tab.id }
            if (index < 0) s.tabs += tab else s.tabs[index] = tab
        }
        if (s.selected != CHAT && s.tabs.none { it.id == s.selected }) s.selected = CHAT
        if (before != s.tabs) changed()
    }
    fun close(chat: String, id: String) {
        if (id == CHAT) return
        val s = session(chat)
        if (s.tabs.removeAll { it.id == id }) {
            s.positions.remove(id)
            if (s.selected == id) s.selected = CHAT
            changed()
        }
    }
    fun position(chat: String, id: String) = session(chat).positions[id] ?: 0
    fun rememberPosition(chat: String, id: String, offset: Int) { session(chat).positions[id] = offset.coerceAtLeast(0) }
}
