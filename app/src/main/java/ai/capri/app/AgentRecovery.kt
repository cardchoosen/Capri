package ai.capri.app

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Internal task checkpoint, separate from visible chat. Never stores credentials or full profile results. */
internal class AgentRecovery(private val root: File) {
    private fun file(chat: String): AtomicFile {
        require(chat.matches(Regex("[a-zA-Z0-9-]+")))
        check(root.mkdirs() || root.isDirectory)
        return AtomicFile(File(root, "$chat.json"))
    }
    fun load(chat: String): JSONArray? = runCatching {
        JSONArray(file(chat).openRead().bufferedReader().use { it.readText() }).takeIf {
            it.length() > 0 && it.optJSONObject(0)?.optString("role") == "system"
        }
    }.getOrNull()
    fun save(chat: String, messages: JSONArray) {
        val target = file(chat)
        val stream = target.startWrite()
        try {
            stream.write(RecoveryMessages.sanitized(messages).toString().toByteArray())
            target.finishWrite(stream)
        } catch (error: Exception) { target.failWrite(stream); throw error }
    }
    fun clear(chat: String) { file(chat).delete() }
}

internal object RecoveryMessages {
    fun isContinuation(text: String) = text.trim().lowercase() in setOf("继续", "继续吧", "重试", "再试一次", "?", "？", "continue", "retry")
    fun sanitized(messages: JSONArray): JSONArray {
        val copy = JSONArray(messages.toString())
        val internalIds = mutableSetOf<String>()
        for (i in 0 until copy.length()) {
            val m = copy.getJSONObject(i)
            if (m.optString("role") == "system") m.put("content", "任务检查点；运行规则在恢复时重新加载。")
            val calls = m.optJSONArray("tool_calls")
            if (calls != null) for (j in 0 until calls.length()) {
                val call = calls.getJSONObject(j)
                if (call.getJSONObject("function").optString("name") in setOf("profile_search", "knowledge", "context_history")) {
                    internalIds += call.getString("id")
                    // Remember/index arguments may themselves contain private knowledge. Do not replay from archives.
                    if (call.getJSONObject("function").optString("name") == "knowledge") call.getJSONObject("function").put("arguments", "{}")
                }
            }
            if (m.optString("role") == "tool" && m.optString("tool_call_id") in internalIds)
                m.put("content", "内部档案结果或知识检索结果未持久化，需要时重新检索当前启用资料。")
        }
        return copy
    }
    /** Fill missing results without dispatching anything: a crash/cancel may leave a side effect unrecorded. */
    fun forResume(saved: JSONArray): JSONArray {
        val results = mutableSetOf<String>()
        for (i in 0 until saved.length()) saved.getJSONObject(i).let {
            if (it.optString("role") == "tool") results += it.optString("tool_call_id")
        }
        val copy = JSONArray()
        for (i in 0 until saved.length()) {
            val m = JSONObject(saved.getJSONObject(i).toString())
            copy.put(m)
            val calls = m.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) {
                val call = calls.getJSONObject(j)
                if (call.getString("id") !in results) copy.put(JSONObject().put("role", "tool")
                    .put("tool_call_id", call.getString("id"))
                    .put("content", "结果未记录：操作可能尚未执行，也可能已产生副作用。禁止直接重跑；先查询所属终端/工作区和服务状态，核实后再决定。"))
            }
        }
        return copy
    }
    fun evidence(saved: JSONArray): String {
        val public = mutableListOf<String>()
        for (i in 0 until saved.length()) saved.getJSONObject(i).let {
            if (it.optString("role") == "tool" && !it.optString("content").startsWith("内部档案结果"))
                public += it.optString("content").take(5000)
        }
        return public.takeLast(8).joinToString("\n").take(30_000)
    }
}
