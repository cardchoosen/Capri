package ai.capri.app

import org.json.JSONObject
import java.io.File

/** Bounded local diagnostics, not a provider bill or an all-time accounting ledger. */
internal object LocalUsage {
    data class Snapshot(val turns: Int = 0, val attempts: Int = 0, val reported: Int = 0,
        val input: Long = 0, val output: Long = 0, val durationMs: Long = 0, val warnings: Int = 0, val limited: Boolean = false)
    fun read(directories: List<File>, limit: Int = 120): Snapshot {
        require(limit in 1..120)
        val files = directories.flatMap { it.listFiles().orEmpty().filter { f -> f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath()) && f.extension == "jsonl" } }.sortedByDescending { it.lastModified() }
        var result = Snapshot(turns = minOf(files.size,limit), limited = files.size > limit)
        for(file in files.take(limit)) {
            if(file.length() > 262144) { result = result.copy(warnings = result.warnings + 1); continue }
            runCatching {
                file.useLines { lines -> lines.forEach { line ->
                    val event = runCatching { JSONObject(line) }.getOrNull()
                    if(event == null) { result = result.copy(warnings = result.warnings + 1); return@forEach }
                    if(event.optString("stage") == "model_request") result = result.copy(attempts = result.attempts + 1, durationMs = result.durationMs + event.optLong("duration_ms").coerceIn(0,86_400_000))
                    if(event.optString("stage") == "model_parse" && event.optString("status") == "ok") {
                        val usage = event.optJSONObject("usage")
                        fun tokens(key: String): Long? = (usage?.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 && it <= 1e12 && it % 1 == 0.0 }?.toLong()
                        val input = tokens("prompt_tokens"); val output = tokens("completion_tokens")
                        if(input != null && output != null) result = result.copy(reported = result.reported + 1, input = result.input + input, output = result.output + output)
                    }
                } }
            }.onFailure { result = result.copy(warnings = result.warnings + 1) }
        }
        return result
    }
}
