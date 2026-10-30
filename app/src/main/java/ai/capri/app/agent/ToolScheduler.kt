package ai.capri.app.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/** Consecutive known-safe reads may overlap; every other call is an ordered barrier. */
internal class ToolScheduler(private val concurrency: Int = 4) {
    companion object {
        private val reads = setOf("read_file", "list_files", "search_files")
        fun parallelSafe(call: JSONObject): Boolean = call.optJSONObject("function")?.optString("name") in reads
    }
    private fun skipped() = JSONObject().put("status", "not_executed").put("reason", "user_input_or_waiting").put("notice", "No dispatch occurred. Reassess the latest user input before proposing further operations.").toString()
    suspend fun run(calls: JSONArray, execute: suspend (JSONObject) -> String, record: suspend (JSONObject, String) -> Unit) = runControlled(calls, execute, record) { true }
    suspend fun runControlled(calls: JSONArray, execute: suspend (JSONObject) -> String, record: suspend (JSONObject, String) -> Unit, canExecute: () -> Boolean) {
        val commits = Mutex()
        var index = 0
        while (index < calls.length()) {
            val call = calls.getJSONObject(index)
            if (!parallelSafe(call)) {
                record(call, if (canExecute()) execute(call) else skipped()); index++; continue
            }
            val group = mutableListOf<JSONObject>()
            while (index < calls.length() && parallelSafe(calls.getJSONObject(index))) group += calls.getJSONObject(index++)
            coroutineScope {
                val slots = Semaphore(concurrency)
                group.map { item -> async {
                    val result = slots.withPermit { if (canExecute()) execute(item) else skipped() }
                    // Commit each completed result even if a sibling later fails or is cancelled.
                    commits.withLock { record(item, result) }
                } }.awaitAll()
            }
        }
    }
}
