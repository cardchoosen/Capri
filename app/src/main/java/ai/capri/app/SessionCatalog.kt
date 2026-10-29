package ai.capri.app

import org.json.JSONArray
import org.json.JSONObject

/** Old sessions remain task sessions. A title is never used to infer personality or identity. */
internal object SessionCatalog {
    fun ensurePersonal(sessions: MutableList<Session>): MutableList<Session> {
        var found = false
        for (i in sessions.indices) if (sessions[i].kind == SessionKind.PERSONAL) {
            if (!found) found = true else sessions[i] = sessions[i].copy(kind = SessionKind.TASK)
        }
        if (!found) sessions.add(0, Session(title = "Capri", kind = SessionKind.PERSONAL))
        return sessions
    }

    fun decode(raw: String): MutableList<Session> {
        val data = JSONArray(raw)
        val sessions = (0 until data.length()).map { i ->
            val obj = data.getJSONObject(i)
            val id = obj.getString("id")
            fun entries(data: JSONArray, legacy: Boolean = false): MutableList<Entry> = (0 until data.length()).map { j ->
                data.getJSONObject(j).let { e -> Entry(e.getString("role"), e.getString("text"),
                    finalReply = e.optBoolean("final_reply", false), timestamp = e.optLong("timestamp", 0),
                    id = e.optString("id").ifBlank { require(legacy) { "Branch message ID missing." }; java.util.UUID.nameUUIDFromBytes((id + ":" + j + ":" + e.toString()).toByteArray()).toString() }) }
            }.toMutableList()
            val messages = entries(obj.getJSONArray("messages"), true)
            val revision=obj.optString("context_version",obj.optString("active_branch",HistoryEditing.INITIAL))
            require(HistoryEditing.validId(revision)) { "Invalid history revision." }
            require(messages.map { it.id }.distinct().size==messages.size) { "Duplicate message ID." }
            Session(id,obj.getString("title"),messages,if(obj.optString("kind")=="PERSONAL")SessionKind.PERSONAL else SessionKind.TASK,
                revision,obj.optLong("history_version",obj.optLong("branch_revision",0)).also{require(it>=0)},
                obj.optBoolean("history_edited",obj.optJSONArray("branches")?.length()?.let{it>0}==true),
                obj.optBoolean("legacy_context",!obj.has("context_version")&&revision!=HistoryEditing.INITIAL))
        }.toMutableList()
        require(sessions.map { it.id }.distinct().size == sessions.size) { "会话ID重复，请恢复会话索引" }
        return ensurePersonal(sessions)
    }

    private fun entry(entry: Entry) = JSONObject().put("id", entry.id).put("role", entry.role).put("text", entry.text)
        .put("final_reply", entry.finalReply).put("timestamp", entry.timestamp)
    fun encode(sessions: List<Session>): String = JSONArray(sessions.map { s ->
        JSONObject().put("id",s.id).put("title",s.title).put("kind",s.kind.name)
            .put("messages",JSONArray(s.messages.map(::entry))).put("context_version",s.contextVersion).put("history_version",s.historyVersion)
            .put("history_edited",s.historyEdited).put("legacy_context",s.legacyContext)
    }).toString()
}
