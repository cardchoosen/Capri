package ai.capri.app

import java.util.UUID

/** One visible timeline. Editing replaces its suffix; execution state is never rewound. */
internal object HistoryEditing {
    const val INITIAL = "main"
    fun validId(id: String) = id == INITIAL || runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    fun contextKey(chat: Session) = if (chat.contextVersion == INITIAL) chat.id else "${chat.id}:history:${chat.contextVersion}"
    fun scope(key: String): Triple<String, String?, Boolean> {
        val legacy = key.contains(":branch:")
        val parts = key.split(if (legacy) ":branch:" else ":history:")
        require(parts.size in 1..2 && parts[0].matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid history scope." }
        val revision = parts.getOrNull(1)
        require(revision == null || revision != INITIAL && validId(revision)) { "Invalid history revision." }
        return Triple(parts[0],revision,legacy)
    }
    fun copy(chat: Session) = chat.copy(messages = chat.messages.toMutableList())
    fun restore(chat: Session, previous: Session) {
        chat.messages.clear();chat.messages.addAll(previous.messages)
        chat.contextVersion=previous.contextVersion;chat.historyVersion=previous.historyVersion;chat.historyEdited=previous.historyEdited;chat.legacyContext=previous.legacyContext;chat.title=previous.title
    }
    fun replaceFrom(chat: Session, entryId: String): Entry {
        val index=chat.messages.indexOfFirst{it.id==entryId&&it.role=="user"}
        require(index>=0) { "用户消息不存在，请刷新会话" }
        val original=chat.messages[index]
        chat.messages.subList(index,chat.messages.size).clear()
        chat.contextVersion=UUID.randomUUID().toString();chat.historyVersion++;chat.historyEdited=true;chat.legacyContext=false
        return original
    }
    fun actualContextKey(chat: Session) = if (chat.legacyContext && chat.contextVersion != INITIAL) "${chat.id}:branch:${chat.contextVersion}" else contextKey(chat)
    const val guidance="This timeline was revised from a historical user message. Its removed suffix is not part of this conversation. Workspace files, terminal processes, accounts and explicit stored resources retain their current state; earlier tool side effects were not rolled back. Inspect actual state before repeating consequential operations. Do not reconstruct removed messages as current conversation history."
}
