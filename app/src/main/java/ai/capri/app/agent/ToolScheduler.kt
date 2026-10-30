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
        private val disk = Semaphore(4)
        private val network = Semaphore(2)
        private val cpu = Semaphore(1)
        private val reads = setOf("read_file", "list_files", "search_files", "project", "web_search", "web_read")
        suspend fun <T> withCpu(block: suspend () -> T): T = cpu.withPermit { block() }
        private suspend fun limited(call: JSONObject, execute: suspend (JSONObject) -> String, canExecute: () -> Boolean): String {
            suspend fun dispatch() = if(canExecute())execute(call) else JSONObject().put("status","not_executed").put("reason","user_input_or_waiting").toString()
            val name=call.getJSONObject("function").getString("name")
            return when(name) {
                "web_search","web_read" -> network.withPermit { dispatch() }
                "read_file","list_files","search_files","project","write_file","edit_file","apply_patch" -> disk.withPermit { dispatch() }
                "python_run" -> withCpu { dispatch() }
                else -> dispatch()
            }
        }
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
                record(call, if (canExecute()) limited(call,execute,canExecute) else skipped()); index++; continue
            }
            val group = mutableListOf<JSONObject>()
            while (index < calls.length() && parallelSafe(calls.getJSONObject(index))) group += calls.getJSONObject(index++)
            coroutineScope {
                val slots = Semaphore(concurrency)
                group.map { item -> async {
                    val result = slots.withPermit { if (canExecute()) limited(item,execute,canExecute) else skipped() }
                    // Commit each completed result even if a sibling later fails or is cancelled.
                    commits.withLock { record(item, result) }
                } }.awaitAll()
            }
        }
    }
}
