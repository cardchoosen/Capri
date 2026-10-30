package ai.capri.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Bounded feedback, persisted as hashes only. Never caches or replays tool operations. */
internal class ProgressGuard(saved: JSONObject? = null) {
    private val recent = ArrayDeque<String>().apply { saved?.optJSONArray("recent")?.let { a -> (0 until a.length()).toList().takeLast(12).forEach { addLast(a.getString(it)) } } }
    var stalled = false
        private set
    fun reset() { recent.clear(); stalled = false }
    fun snapshot() = JSONObject().put("recent", JSONArray(recent.toList()))
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    fun observe(call: JSONObject, result: String): String? {
        val function = runCatching { ToolRegistry.function(call) }.getOrDefault(call.getJSONObject("function")); val name = function.getString("name")
        if (name in setOf("task_state", "ask_user")) return null
        val args = runCatching { JSONObject(function.optString("arguments")) }.getOrNull()
        val data = runCatching { JSONObject(result) }.getOrNull()
        val poll = name == "terminal" && args?.optString("action") == "read"
        val kind = if (poll) "unchanged_poll" else if (CompletionEvidence.unsuccessful(result)) "repeated_failure" else "repeated_operation"
        val stable = data?.let { JSONObject(it.toString()).apply { listOf("elapsed_ms", "duration_ms", "timestamp").forEach(::remove) } }
        if (poll) args?.remove("wait_ms")
        val signature = name + "\u0000" + (args?.let(::canonical) ?: function.optString("arguments")) + "\u0000" + (stable?.let(::canonical) ?: result)
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        recent.addLast(digest); if (recent.size > 12) recent.removeFirst()
        val count = recent.count { it == digest }
        if (count >= 6) stalled = true
        if (count !in setOf(3, 6)) return null
        val advice = when (kind) {
            "unchanged_poll" -> "Use bounded waiting and continuation offsets; stop polling unchanged output."
            "repeated_failure" -> "Inspect the error, correct its cause, change the method, or report the concrete blocker. Do not repeat unchanged failing calls."
            else -> "Reuse recorded evidence or investigate a different uncertainty. Repeating successful actions can duplicate side effects."
        }
        return "Progress feedback: $kind repeated $count times without changed evidence. $advice No operation was skipped or replayed by this guard." + if (stalled) " This run will stop as incomplete with its checkpoint preserved." else ""
    }
}
