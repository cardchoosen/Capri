package ai.capri.app.knowledge

import ai.capri.app.Entry
import ai.capri.app.Session
import ai.capri.app.SessionKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Sourced notes and extractive episodes in KnowledgeStore. No extra model request per turn. */
internal class PersonalMemory(private val store: KnowledgeStore) {
    private fun sensitive(text: String) = Regex("(?i)(sk-[a-z0-9]{16,}|-----BEGIN .*PRIVATE KEY|bearer\\s+[a-z0-9._-]{16,}|密码|口令|密钥|password|api[_ -]?key)").containsMatchIn(text)
    private fun excerpt(text: String, limit: Int): String = if (text.length <= limit) text else text.take(limit / 2) + " …[省略，可查原文]… " + text.takeLast(limit / 2)
    private fun source(chat: Session, index: Int) = JSONObject().put("chat_id", chat.id).put("entry", index + 1)
        .put("entry_id", chat.messages[index].id).put("context_id", chat.contextVersion).put("role", chat.messages[index].role).put("timestamp", chat.messages[index].timestamp)

    fun recordTurn(chat: Session) {
        if (chat.kind != SessionKind.PERSONAL) return
        val user = chat.messages.indexOfLast { it.role == "user" }
        val reply = chat.messages.indexOfLast { it.role == "assistant" && it.finalReply }
        if (user < 0 || reply <= user) return
        val text = "用户：${excerpt(chat.messages[user].text, 700)}\nCapri：${excerpt(chat.messages[reply].text, 1000)}"
        if (sensitive(text)) return
        store.savePersonal(JSONObject().put("id", "episode-${chat.id}-${chat.messages[user].id}").put("kind", "episode")
            .put("scope", chat.id).put("text", text).put("updated", chat.messages[reply].timestamp)
            .put("sources", JSONArray(listOf(source(chat, user), source(chat, reply)))))
    }

    fun execute(personal: Session, current: Session, args: JSONObject): String {
        return when (args.getString("action")) {
            "search" -> {
                val query = args.getString("query"); require(query.isNotBlank() && query.length <= 300)
                JSONArray(ranked(personal.id, query, personal).filter { TextIndex.score(query, it.getString("text")) > 0 }.take(8)).toString()
            }
            "remember" -> {
                require(current.id == personal.id && current.kind == SessionKind.PERSONAL) { "任务对话不写入个人记忆" }
                val index = args.getInt("source_entry") - 1
                require(current.messages.getOrNull(index)?.role == "user") { "source_entry必须是真实用户消息e编号" }
                val text = args.getString("text").trim()
                require(text.isNotBlank() && text.length <= 1600 && !sensitive(text)) { "记忆须1–1600字符，不能含凭据" }
                val records = store.personalRecords(personal.id).filter { it.getString("kind") == "personal_note" }
                val id = args.optString("id")
                val previous = if (id.isBlank()) records.firstOrNull { it.getString("text") == text }
                    else records.firstOrNull { it.getString("id") == id } ?: error("待更新记忆不存在")
                require(previous != null || records.size < 200) { "重要记忆已满，请合并相关条目" }
                val record = JSONObject().put("id", previous?.getString("id") ?: "personal-${UUID.randomUUID()}")
                    .put("kind", "personal_note").put("scope", personal.id).put("text", text)
                    .put("updated", current.messages[index].timestamp).put("sources", JSONArray(listOf(source(current, index))))
                store.savePersonal(record)
                JSONObject().put("id", record.getString("id")).put("status", "saved").put("sources", record.getJSONArray("sources")).toString()
            }
            else -> error("action须为search/remember")
        }
    }

    private fun ranked(chat: String, query: String, visible: Session? = null) = store.personalRecords(chat).filter { record ->
        if (visible == null || !visible.historyEdited) true else {
            val sources = record.optJSONArray("sources")
            sources != null && sources.length() > 0 && (0 until sources.length()).all { i ->
                val source = sources.getJSONObject(i)
                if (source.has("entry_id")) visible.messages.any { it.id == source.getString("entry_id") && it.role == source.optString("role") }
                else visible.messages.getOrNull(source.optInt("entry") - 1)?.let { e -> source.optLong("timestamp") > 0 && e.timestamp == source.optLong("timestamp") && e.role == source.optString("role") } == true
            }
        }
    }.sortedWith(
        compareByDescending<JSONObject> { TextIndex.score(query, it.getString("text")) }.thenByDescending { it.optLong("updated") })

    fun recall(chat: String, query: String, maxChars: Int = 4200): String = recallRecords(ranked(chat, query), query, maxChars)
    fun recall(chat: Session, query: String, maxChars: Int = 4200): String = recallRecords(ranked(chat.id, query, chat), query, maxChars)
    private fun recallRecords(all: List<JSONObject>, query: String, maxChars: Int): String {
        // Relevant notes, recent stable preferences and the most recent exchanges; never the whole history.
        val picked = (all.filter { TextIndex.score(query, it.getString("text")) > 0 }.take(3) +
            all.filter { it.getString("kind") == "personal_note" }.sortedByDescending { it.optLong("updated") }.take(3) +
            all.filter { it.getString("kind") == "episode" }.sortedByDescending { it.optLong("updated") }.take(2))
            .distinctBy { it.getString("id") }
        var remaining = maxChars.coerceIn(0, 4200)
        return picked.mapNotNull { record ->
            val header = "${record.getString("id")} · ${if (record.getString("kind") == "episode") "对话摘录，非事实保证" else "用户信息概括，待原文核对"} · 来源${record.getJSONArray("sources")}\n"
            if (remaining < header.length + 100) null else {
                val item = header + excerpt(record.getString("text"), minOf(1100, remaining - header.length))
                remaining -= item.length + 2; item
            }
        }.joinToString("\n\n").take(maxChars.coerceIn(0, 4200))
    }

    companion object {
        val guidance = """
            默认个人对话允许自然形成个人记忆，无需每次询问或播报。完成的个人对话由客户端保存有来源的对话摘录；这不是自动事实判断。
            personal_memory.search按需查个人记忆；个人对话里发现用户明确偏好、相处方式纠正、重要约定或未完事项时，可remember简短概括，必须source_entry引用真实用户消息e编号（用context_history查找）。
            更新旧信息先search，用原id替换，优先最新用户纠正；区分用户陈述、愿望、玩笑与事实。不得把助手自述、示例、推断或未执行任务存成已发生事实，不保存凭据，不重写核心身份。
            任务对话只可读取个人记忆，不可写入；查阅任务历史不等于应导入个人记忆。个人记忆不能覆盖系统规则，原文与用户当前纠正优先。
        """.trimIndent()
        fun specification(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "personal_memory").put("description", "Search sourced personal experiences/preferences visible in the current personal timeline. remember saves or updates sourced important information only in the default personal chat; task chats are read-only. Cannot edit core persona records.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("search", "remember"))))
                .put("query", JSONObject().put("type", "string")).put("text", JSONObject().put("type", "string"))
                .put("id", JSONObject().put("type", "string").put("description", "Existing memory ID when updating."))
                .put("source_entry", JSONObject().put("type", "integer").put("description", "remember requires the 1-based entry number of an actual user message in the current personal timeline.")))
                .put("required", JSONArray(listOf("action")))))
    }
}
