package ai.capri.app.knowledge

import ai.capri.app.Entry
import ai.capri.app.Session
import ai.capri.app.SessionKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Sourced notes and extractive episodes in KnowledgeStore. No extra model request per turn. */
internal class PersonalMemory(private val store: KnowledgeStore) {
    private fun sensitive(text: String) = MemoryPolicy.sensitive(text)
    private fun excerpt(text: String, limit: Int): String = if (text.length <= limit) text else text.take(limit / 2) + " …[省略，可查原文]… " + text.takeLast(limit / 2)
    private fun source(chat: Session, index: Int) = JSONObject().put("chat_id", chat.id).put("entry", index + 1)
        .put("text_hash", MemoryPolicy.hash(chat.messages[index].text)).put("entry_id", chat.messages[index].id).put("context_id", chat.contextVersion).put("role", chat.messages[index].role).put("timestamp", chat.messages[index].timestamp)

    fun recordTurn(chat: Session) {
        if (chat.kind != SessionKind.PERSONAL) return
        val user = chat.messages.indexOfLast { it.role == "user" }
        val reply = chat.messages.indexOfLast { it.role == "assistant" && it.finalReply }
        if (user < 0 || reply <= user) return
        if(store.personalRecords(chat.id).any { it.optString("state")=="forgotten" && it.optString("forgotten_after_entry_id")==chat.messages[user].id })return
        val text = "用户：${excerpt(chat.messages[user].text, 700)}\nCapri：${excerpt(chat.messages[reply].text, 1000)}"
        if (sensitive(text)) return
        store.savePersonal(JSONObject().put("id", "episode-${chat.id}-${chat.messages[user].id}").put("kind", "episode")
            .put("scope", chat.id).put("text", text).put("updated", chat.messages[reply].timestamp)
            .put("sources", JSONArray(listOf(source(chat, user), source(chat, reply)))))
    }

    fun execute(personal: Session, current: Session, args: JSONObject): String {
        validate(args)
        return when (args.getString("action")) {
            "forget" -> {
                require(current.id == personal.id && current.kind == SessionKind.PERSONAL) { "Task chats cannot write personal memories." }
                val old = store.personalRecords(personal.id).firstOrNull { it.optString("id") == args.getString("id") && it.optString("kind") == "personal_note" } ?: error("Memory not found.")
                require(!args.has("expected_revision") || args.getInt("expected_revision") == old.optInt("revision",1)) { "Memory revision conflict." }
                store.savePersonal(JSONObject(old.toString()).put("state","forgotten").put("forgotten_at",System.currentTimeMillis()).put("forgotten_after_entry_id",current.messages.lastOrNull { it.role=="user" }?.id.orEmpty()).put("revision",old.optInt("revision",1)+1))
                JSONObject().put("status","forgotten").put("id",old.getString("id")).toString()
            }
            "search" -> {
                val query = args.getString("query"); require(query.isNotBlank() && query.length <= 300)
                JSONArray(ranked(personal.id, query, personal).filter { TextIndex.score(query, it.getString("text")) > 0 }.take(8)).toString()
            }
            "remember" -> {
                require(current.id == personal.id && current.kind == SessionKind.PERSONAL) { "Task chats cannot write personal memories." }
                val index = args.getInt("source_entry") - 1
                require(current.messages.getOrNull(index)?.role == "user") { "source_entry must identify a real user message in the current timeline." }
                val text = args.getString("text").trim()
                require(text.isNotBlank() && text.length <= 1600 && !sensitive(text)) { "Memory text must contain 1–1600 characters and no credentials." }
                val visibleIds=active(personal.id,personal).map { it.getString("id") }.toSet()
                val records = store.personalRecords(personal.id).filter { it.getString("kind") == "personal_note" }
                val category = args.optString("category", "user_fact")
                require(category in MemoryPolicy.personal) { "Use knowledge for project rules, environment for installation facts, task_state for progress, and task_experience for procedures." }
                val key = args.optString("key")
                require(key.length <= 80 && (key.isBlank() || key.matches(Regex("[a-z0-9_.-]+")))) { "key must be a stable English topic identifier." }
                val id = args.optString("id")
                val previous = if (id.isBlank()) records.firstOrNull { if (key.isNotBlank()) it.optString("key") == key else it.getString("text") == text }
                    else records.firstOrNull { it.getString("id") == id } ?: error("Memory to update was not found.")
                if(previous!=null && previous.optLong("expires_at") in 1..System.currentTimeMillis()) {
                    val oldSources=previous.optJSONArray("sources") ?: JSONArray()
                    require((0 until oldSources.length()).none { oldSources.getJSONObject(it).optString("entry_id")==current.messages[index].id }) { "An expired topic requires a new user source before renewal." }
                }
                if(previous?.optString("state")=="forgotten") {
                    val boundary=current.messages.indexOfFirst { it.id==previous.optString("forgotten_after_entry_id") }
                    require(if(boundary>=0) index>boundary else current.messages[index].timestamp>previous.optLong("forgotten_at")) { "A forgotten topic requires a new user source before it can be remembered again." }
                }
                require(previous == null || current.messages[index].timestamp >= previous.optLong("updated")) { "An older source cannot replace a newer correction." }
                require(previous == null || !args.has("expected_revision") || args.getInt("expected_revision") == previous.optInt("revision",1)) { "Memory revision conflict; search current notes first." }
                val expires = args.optLong("expires_at",0)
                require(expires == 0L || expires > System.currentTimeMillis()) { "expires_at must be a future epoch millisecond value or 0." }
                require(previous != null || records.count { it.getString("id") in visibleIds } < 200) { "Personal memory capacity reached; merge or forget obsolete notes." }
                val record = JSONObject().put("id", previous?.getString("id") ?: "personal-${UUID.randomUUID()}")
                    .put("kind", "personal_note").put("scope", personal.id).put("category",category).put("key",key.ifBlank { previous?.optString("key").orEmpty() })
                    .put("state","active").put("expires_at",expires).put("revision",(previous?.optInt("revision",1) ?: 0)+1).put("text", text)
                    .put("updated", current.messages[index].timestamp).put("sources", JSONArray(listOf(source(current, index))))
                store.savePersonal(record,visibleIds)
                JSONObject().put("id", record.getString("id")).put("status", "saved").put("revision",record.getInt("revision")).put("sources", record.getJSONArray("sources")).toString()
            }
            else -> error("Unknown personal_memory action.")
        }
    }

    internal fun active(chat: String, visible: Session? = null): List<JSONObject> {
        val byId=visible?.messages?.associateBy { it.id }
        val hashes=mutableMapOf<String,String>()
        val records=store.personalRecords(chat)
        val blocked=records.filter { it.optString("kind")=="personal_note" && (it.optString("state","active")!="active" || it.optLong("expires_at") in 1..System.currentTimeMillis()) }.flatMap { note ->
            note.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("role")=="user" }.map { it.optString("entry_id") } } ?: emptyList()
        }.filter { it.isNotBlank() }.toSet()
        return records.filter { record ->
        (record.optString("kind")!="episode" || record.optJSONArray("sources")?.let { a -> (0 until a.length()).none { a.getJSONObject(it).optString("entry_id") in blocked } }==true) &&
        record.optString("state","active") == "active" && (record.optLong("expires_at") == 0L || record.optLong("expires_at") > System.currentTimeMillis()) &&
            (visible == null || record.optJSONArray("sources")?.let { sources -> sources.length() > 0 && (0 until sources.length()).all { i ->
                val source=sources.getJSONObject(i)
                val entry=if(source.has("entry_id")) byId?.get(source.getString("entry_id"))?.takeIf { it.role==source.optString("role") }
                    else visible.messages.getOrNull(source.optInt("entry")-1)?.takeIf { source.optLong("timestamp")>0 && it.timestamp==source.optLong("timestamp") && it.role==source.optString("role") }
                entry != null && (!source.has("text_hash") || source.getString("text_hash")==hashes.getOrPut(entry.id) { MemoryPolicy.hash(entry.text) })
            } } == true)
    }
    }
    private fun ranked(chat: String, query: String, visible: Session? = null) = active(chat,visible).sortedWith(
        compareByDescending<JSONObject> { TextIndex.score(query,it.getString("text")) }.thenByDescending { it.optLong("updated") })

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
        val guidance = MemoryPolicy.guidance + " Personal chats may remember concise explicit user preferences/corrections without repeated confirmation; task chats are read-only. remember requires a real user source_entry. Search first and update the same id/key for a correction; older sources cannot overwrite newer ones. Use expires_at for temporary commitments. forget deactivates a note; it does not erase original messages. Never save credentials, guesses, assistant self-claims or core identity. Search or read original history before claiming to remember an event."
        fun validate(args: JSONObject) {
            val action=args.optString("action")
            val fields=when(action) { "search" -> setOf("action","query"); "remember" -> setOf("action","text","source_entry","id","category","key","expires_at","expected_revision"); "forget" -> setOf("action","id","expected_revision"); else -> error("Unknown personal_memory action.") }
            require(args.keys().asSequence().all { it in fields }) { "Unknown personal_memory argument." }
            for(key in listOf("id","category","key")) if(args.has(key)) require(args.opt(key) is String) { "$key must be text." }
            if(action=="search") require(args.opt("query") is String && args.getString("query").length in 1..300) { "query must contain 1–300 characters." }
            if(action=="remember") { require(args.opt("text") is String && args.opt("source_entry") is Int && args.getInt("source_entry")>0) { "remember requires text and a positive source_entry." } }
            if(action=="forget") require(args.opt("id") is String && args.getString("id").isNotBlank()) { "forget requires an existing id." }
            if(args.has("expected_revision")) require(args.opt("expected_revision") is Int && args.getInt("expected_revision")>0) { "expected_revision must be a positive integer." }
            if(args.has("expires_at")) require(args.opt("expires_at") is Number && args.getDouble("expires_at")==args.getLong("expires_at").toDouble()) { "expires_at must be an integer epoch millisecond value." }
        }
        fun specification(): JSONObject {
            fun text(max: Int)=JSONObject().put("type","string").put("maxLength",max)
            val properties=JSONObject().put("query",text(300).put("minLength",1)).put("text",text(1600).put("minLength",1))
                .put("id",text(200).put("description","Existing memory ID for update or forget."))
                .put("category",text(32).put("enum",JSONArray(MemoryPolicy.personal.toList())))
                .put("key",text(80).put("description","Stable English topic identifier; reuse for corrections."))
                .put("expires_at",JSONObject().put("type","integer").put("minimum",0).put("description","Future epoch milliseconds, or 0 for no expiry."))
                .put("expected_revision",JSONObject().put("type","integer").put("minimum",1).put("maximum",Int.MAX_VALUE))
                .put("source_entry",JSONObject().put("type","integer").put("minimum",1).put("maximum",Int.MAX_VALUE).put("description","Actual user message number in this personal timeline, counted from 1."))
            val fields=mapOf("search" to setOf("query"),"remember" to setOf("text","source_entry","id","category","key","expires_at","expected_revision"),"forget" to setOf("id","expected_revision"))
            val schema=ai.capri.app.ActionSchema.build(properties,fields,required={ action -> when(action) { "search" -> listOf("query");"remember" -> listOf("text","source_entry");else -> listOf("id") } })
            return JSONObject().put("type","function").put("function",JSONObject().put("name","personal_memory")
                .put("description","Search sourced personal notes and episodes in the current personal timeline. remember adds/updates explicit user facts/preferences/open items only in the default personal chat; reuse id/key for corrections. forget deactivates a note and prevents replaying its old source to reactivate it. Task chats are read-only. Capacity: 200 active notes / 32000 text characters; merge or forget obsolete notes. Expired or revoked sources are not recalled. Source summaries are not independent proof or core persona records.")
                .put("parameters",schema))
        }
    }
}
