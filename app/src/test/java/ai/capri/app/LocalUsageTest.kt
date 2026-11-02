package ai.capri.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalUsageTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun aggregatesReportedUsageOnceAndRetainsRetriesWithoutInventingTokens() {
        File(temp.root,"turn.jsonl").writeText("""
            {"stage":"model_request","duration_ms":10,"status":"error"}
            {"stage":"model_request","duration_ms":20,"status":"ok","usage":{"prompt_tokens":999}}
            {"stage":"model_parse","status":"ok","usage":{"prompt_tokens":5,"completion_tokens":3}}
            {"stage":"model_parse","status":"ok","usage":{"prompt_tokens":8}}
            {"stage":"model_parse","status":"error","usage":{"prompt_tokens":500,"completion_tokens":500}}
            {"stage":"model_parse","status":"ok","usage":{"prompt_tokens":-1,"completion_tokens":3}}
            {"stage":"model_parse","status":"ok","usage":{"prompt_tokens":0,"completion_tokens":0}}
        """.trimIndent())
        val data = LocalUsage.read(listOf(temp.root))
        assertEquals(2,data.attempts); assertEquals(2,data.reported)
        assertEquals(5L,data.input); assertEquals(3L,data.output); assertEquals(30L,data.durationMs)
    }
    @Test fun boundedRecentRecordsTolerateTruncatedLinesAndOversizedFiles() {
        val old = File(temp.root,"old.jsonl").apply { writeText("{\"stage\":\"model_request\"}\n");setLastModified(1000) }
        File(temp.root,"recent.jsonl").apply { writeText("{\"stage\":\"model_request\"}\n{partial");setLastModified(2000) }
        val recent = LocalUsage.read(listOf(temp.root),1)
        assertEquals(1,recent.turns);assertEquals(1,recent.attempts);assertEquals(1,recent.warnings);assertTrue(recent.limited)
        old.writeText("x".repeat(262145));old.setLastModified(3000)
        val oversized = LocalUsage.read(listOf(temp.root),1)
        assertEquals(0,oversized.attempts);assertEquals(1,oversized.warnings)
    }
}
