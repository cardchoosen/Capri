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
            val messages = obj.getJSONArray("messages")
            Session(obj.getString("id"), obj.getString("title"), (0 until messages.length()).map { j ->
                messages.getJSONObject(j).let { Entry(it.getString("role"), it.getString("text"),
                    finalReply = it.optBoolean("final_reply", false), timestamp = it.optLong("timestamp", 0)) }
            }.toMutableList(), if (obj.optString("kind") == "PERSONAL") SessionKind.PERSONAL else SessionKind.TASK)
        }.toMutableList()
        require(sessions.map { it.id }.distinct().size == sessions.size) { "会话ID重复，请恢复会话索引" }
        return ensurePersonal(sessions)
    }

    fun encode(sessions: List<Session>): String = JSONArray(sessions.map { s ->
        JSONObject().put("id", s.id).put("title", s.title).put("kind", s.kind.name)
            .put("messages", JSONArray(s.messages.map { entry -> JSONObject().put("role", entry.role).put("text", entry.text)
                .put("final_reply", entry.finalReply).put("timestamp", entry.timestamp) }))
    }).toString()
}
