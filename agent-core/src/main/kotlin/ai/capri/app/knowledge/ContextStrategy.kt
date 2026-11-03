package ai.capri.app.knowledge

import ai.capri.app.model.ContextBudget
import ai.capri.app.model.ContextTokens
import org.json.JSONArray
import org.json.JSONObject

data class ContextDecision(val target: ContextBudget, val maximumGroups: Int = Int.MAX_VALUE, val forceOne: Boolean = false)
interface ContextStrategy {
    val name: String
    fun decide(messages: JSONArray, tools: JSONArray, budget: ContextBudget): ContextDecision?
}
class PressureContextStrategy : ContextStrategy {
    override val name = "pressure"
    override fun decide(messages: JSONArray, tools: JSONArray, budget: ContextBudget): ContextDecision? =
        if (budget.tokens(ContextTokens.estimate(messages, tools)) < budget.input * .85) null
        else ContextDecision(budget.copy(safety = budget.safety + budget.input / 5))
}
/** Experimental, injected explicitly. At most one old group per new user boundary when hard budget fits. */
class MicroContextStrategy : ContextStrategy {
    override val name = "micro"
    companion object {
        fun lastBoundary(messages: JSONArray): JSONObject? = (1 until messages.length()).map { messages.getJSONObject(it) }.lastOrNull {
            it.optString("role") in setOf("user", "assistant", "tool") && !ContextWindow.auxiliary(it)
        }?.takeIf { it.optString("role") == "user" }
        fun boundary(messages: JSONArray): String? = lastBoundary(messages)?.let { m -> m.optString("_capri_user_entry").ifBlank {
            java.security.MessageDigest.getInstance("SHA-256").digest(m.optString("content").toByteArray()).joinToString("") { "%02x".format(it) }
        } }
    }
    override fun decide(messages: JSONArray, tools: JSONArray, budget: ContextBudget): ContextDecision? {
        val estimate = ContextTokens.estimate(messages, tools)
        if (!budget.fits(estimate)) return PressureContextStrategy().decide(messages, tools, budget)
        val last = lastBoundary(messages) ?: return null
        if (last.optString("role") != "user" || budget.tokens(estimate) < budget.input * .65) return null
        val boundary = boundary(messages) ?: return null
        if (messages.getJSONObject(0).optString("_capri_micro_boundary") == boundary) return null
        return ContextDecision(budget, maximumGroups = 1, forceOne = true)
    }
}

/** Pure selection. No history mutation, disk writes, summary calls or tool dispatch. */
object ContextWindow {
    data class Selection(val removed: Set<Int>, val oldNotes: Set<Int>, val protectedUsers: Set<Int>)
    fun note(m: JSONObject) = m.optString("role") == "user" && (m.has("_capri_context_archive") || m.optString("_capri_context_kind") == "archive")
    fun auxiliary(m: JSONObject) = note(m) || m.optString("_capri_context_kind") == "skill"
    private fun user(m: JSONObject) = m.optString("role") == "user" && !auxiliary(m)
    fun select(messages: JSONArray, tools: JSONArray, budget: ContextBudget, noteReserve: Long, maximumGroups: Int = Int.MAX_VALUE, forceOne: Boolean = false): Selection? {
        val users = (1 until messages.length()).filter { user(messages.getJSONObject(it)) }
        val origin = (messages.optJSONObject(0)?.optJSONObject("_capri_turn") ?: JSONObject()).optString("origin")
        val request = (messages.optJSONObject(0)?.optJSONObject("_capri_task_state") ?: JSONObject()).optString("request")
        val originAt = users.firstOrNull { origin.isNotBlank() && messages.getJSONObject(it).optString("_capri_user_entry") == origin }
            ?: users.lastOrNull { val content = messages.getJSONObject(it).optString("content")
                request.isNotBlank() && (content == request || content.startsWith(request + "\n") || content.startsWith(request + "；"))
            }
        val protected = mutableSetOf<Int>()
        users.firstOrNull()?.let(protected::add);users.lastOrNull()?.let(protected::add)
        if (originAt != null) protected.addAll(users.filter { it >= originAt })
        val notes = (1 until messages.length()).filter { note(messages.getJSONObject(it)) }.toSet()
        val starts = (1 until messages.length()).filter { val m=messages.getJSONObject(it); m.optString("role") == "assistant" || user(m) }
        val estimates = (0 until messages.length()).map { ContextTokens.message(messages.getJSONObject(it)) }
        var remaining = 16 + ContextTokens.text(tools.toString()) + estimates.sum()
        if (budget.fits(remaining) && !forceOne) return null
        val removed = notes.toMutableSet();notes.forEach { remaining -= estimates[it] }
        var groups = 0
        // Keep the latest group intact. Every assistant call and all its results form one group.
        for ((position, first) in starts.withIndex()) {
            if (groups > 0 && budget.fits(remaining + noteReserve) && (!forceOne || groups >= 1)) break
            if (!forceOne && budget.fits(remaining + noteReserve)) break
            if (groups >= maximumGroups) break
            val end = starts.getOrNull(position + 1) ?: messages.length()
            if (position == starts.lastIndex || (first until end).any { it in protected }) continue
            val callArray = messages.getJSONObject(first).optJSONArray("tool_calls") ?: JSONArray()
            val callIds = (0 until callArray.length()).map { callArray.getJSONObject(it).getString("id") }
            val results = (first + 1 until end).filter { messages.getJSONObject(it).optString("role") == "tool" }.map { messages.getJSONObject(it).getString("tool_call_id") }
            require(callIds.distinct().size == callIds.size && results.distinct().size == results.size && callIds.toSet() == results.toSet()) { "Incomplete or ambiguous tool exchange; preserve the checkpoint and verify effects before compaction." }
            for (i in first until end) if (removed.add(i)) remaining -= estimates[i]
            groups++
        }
        if (groups == 0) {
            if (budget.fits(ContextTokens.estimate(messages, tools))) return null
            throw ContextBudgetFailure("Protected user requirements, latest exchange or tools exceed the context budget; narrow inputs without discarding constraints.")
        }
        if (!budget.fits(remaining + noteReserve)) throw ContextBudgetFailure("Protected user requirements and retained exchanges exceed the context budget; the original checkpoint is preserved.")
        return Selection(removed, notes, protected)
    }
}
