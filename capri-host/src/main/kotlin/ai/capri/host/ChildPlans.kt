package ai.capri.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class ChildPlans(private val sessions: HostSessions) {
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun execute(session: String, args: JSONObject,
                        analyze: suspend (JSONObject, List<JSONObject>) -> JSONObject): JSONObject {
        when (args.getString("action")) {
            "list" -> return JSONObject().put("plans", JSONArray(sessions.childPlans(session).map { receipt ->
                JSONObject().put("planId", receipt.getString("planId")).put("state", visibleState(receipt))
            }))
            "status" -> {
                val receipt = sessions.childPlan(session, args.optString("planId"))
                    ?: throw WorkspaceFailure("plan_not_found", "No such plan exists in this session.")
                return receipt.put("state", visibleState(receipt))
            }
        }
        val steps = args.optJSONArray("steps")?.let(::jsonObjects).orEmpty()
        if (steps.isEmpty()) throw WorkspaceFailure("invalid_plan", "Provide at least one step.")
        val ids = steps.map { it.getString("id") }
        val dependencies = steps.associate { step ->
            val list = step.optJSONArray("dependsOn") ?: JSONArray()
            step.getString("id") to (0 until list.length()).map(list::getString)
        }
        if (ids.size != ids.toSet().size || ids.any { !it.matches(Regex("[a-zA-Z0-9_-]{1,32}")) } ||
            dependencies.values.any { it.size != it.toSet().size || !ids.containsAll(it) })
            throw WorkspaceFailure("invalid_plan", "Step IDs must be unique and dependencies must reference this plan.")
        val remaining = ids.toMutableList()
        val ordered = mutableSetOf<String>()
        val waves = mutableListOf<List<String>>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { ordered.containsAll(dependencies.getValue(it)) }
            if (ready.isEmpty()) throw WorkspaceFailure("plan_cycle", "Step dependencies must be acyclic.")
            waves.addAll(ready.chunked(3))
            ordered.addAll(ready)
            remaining.removeAll(ready.toSet())
        }

        val planId = UUID.randomUUID().toString()
        val results = linkedMapOf<String, JSONObject>()
        val byId = steps.associateBy { it.getString("id") }
        fun checkpoint(state: String): JSONObject {
            val value = JSONObject().put("planId", planId).put("state", state)
                .put("steps", JSONArray(ids.map { id ->
                    results[id] ?: JSONObject().put("id", id).put("status", "pending")
                }))
            sessions.saveChildPlan(session, value)
            return value
        }
        active.add(planId)
        try {
            checkpoint("running")
            for (wave in waves) {
                val ready = wave.associateWith { id -> dependencies.getValue(id).map(results::getValue) }
                coroutineScope {
                    ready.map { (id, inputs) ->
                        async {
                            val result = if (inputs.any { it.optString("status") != "ok" }) {
                                JSONObject().put("status", "blocked").put("error", "dependency_failed")
                            } else try {
                                analyze(byId.getValue(id), inputs).put("status", "ok")
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (failure: WorkspaceFailure) {
                                JSONObject().put("status", "error").put("error", failure.code)
                            } catch (_: Exception) {
                                JSONObject().put("status", "error").put("error", "delegate_failed")
                            }
                            synchronized(results) {
                                results[id] = result.put("id", id)
                                checkpoint("running")
                            }
                        }
                    }.awaitAll()
                }
            }
            return checkpoint(if (results.values.all { it.optString("status") == "ok" }) "complete" else "partial")
        } catch (failure: CancellationException) {
            checkpoint("interrupted")
            throw failure
        } finally {
            active.remove(planId)
        }
    }

    private fun visibleState(receipt: JSONObject): String = receipt.getString("state").let { state ->
        if (state == "running" && receipt.getString("planId") !in active) "interrupted" else state
    }
}
