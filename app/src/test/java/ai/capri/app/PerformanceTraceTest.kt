package ai.capri.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PerformanceTraceTest {
    @Test fun recordsMonotonicStagesAndOnlyWhitelistedNumericUsage() {
        val directory = Files.createTempDirectory("capri-metrics").toFile()
        try {
            var time = 1_000_000_000L
            val file = directory.resolve("trace.jsonl")
            val trace = PerformanceTrace(file) { time }
            time += 25_000_000
            trace.event(PerformanceTrace.Stage.MODEL_REQUEST, 25, PerformanceTrace.Status.OK, attempt = 1,
                tool = "secret-tool-name", usage = JSONObject().put("prompt_tokens", 42).put("secret", "sk-private").put("completion_tokens", "sk-private"))
            trace.finish(PerformanceTrace.Status.OK)
            trace.event(PerformanceTrace.Stage.MODEL_BODY, 1, PerformanceTrace.Status.OK) // Late network callbacks cannot reopen a finished turn.
            val text = file.readText(); assertFalse(text.contains("sk-private")); assertFalse(text.contains("secret-tool-name"))
            val entries = text.lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
            assertEquals(25L, entries[1].getLong("offset_ms")); assertEquals("unknown", entries[1].getString("tool"))
            assertEquals(42, entries[1].getJSONObject("usage").getInt("prompt_tokens"))
            assertEquals(1, entries[1].getJSONObject("usage").length())
            assertEquals(3, entries.size)
            assertEquals(25L, entries.last().getLong("duration_ms"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun brokenDiagnosticsStorageDoesNotBreakATask() {
        val file = Files.createTempFile("capri-metrics-parent", ".file").toFile()
        try { PerformanceTrace(file.resolve("trace.jsonl")).finish(PerformanceTrace.Status.ERROR) }
        finally { file.delete() }
    }
}
