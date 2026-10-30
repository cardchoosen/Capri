package ai.capri.app

import ai.capri.app.model.ModelCapacity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real AtomicFile checkpoints in an isolated directory; no user chat, memory or device changes. */
@RunWith(AndroidJUnit4::class)
class FinalReplyCheckpointDeviceTest {
    @Test fun pendingSpeechSurvivesDiskReloadAndCompletionKeepsOnlyTheRenderedAnswer() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "final-checkpoint-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val history = JSONArray("""[
                {"role":"system","content":"EXECUTION_ONLY"},
                {"role":"user","content":"Read the device model"},
                {"role":"assistant","tool_calls":[{"id":"read1","type":"function","function":{"name":"android","arguments":"{\"action\":\"device_info\"}"}}]},
                {"role":"tool","tool_call_id":"read1","content":"{\"status\":\"ok\",\"model\":\"fixture-device\"}"},
                {"role":"assistant","content":"Internal draft: device model is fixture-device."}
            ]""")
            val state = FinalReply.mark(history, "Read the device model", "Internal draft: device model is fixture-device.", setOf("read1"))
            AgentRecovery(directory).save("fixture-chat", history)
            val loaded = requireNotNull(AgentRecovery(directory).load("fixture-chat"))
            assertEquals(state.toString(), FinalReply.pending(loaded)!!.toString())
            val input = FinalReply.messages("CHARACTER_FIRST", SessionKind.PERSONAL, "now", "", state, loaded,
                ModelCapacity(32768, 4096, "test").budget())
            assertTrue(input.getJSONObject(0).getString("content").startsWith("CHARACTER_FIRST"))
            assertFalse(input.toString().contains("EXECUTION_ONLY"))
            assertFalse(FinalReply.body(ModelConfig(model = "fixture"), input, 1024).has("tools"))
            // A cancelled/failed network render leaves the disk checkpoint unchanged.
            assertEquals(state.toString(), FinalReply.pending(AgentRecovery(directory).load("fixture-chat"))!!.toString())
            FinalReply.complete(loaded, state, "型号找到了，是fixture-device。")
            AgentRecovery(directory).save("fixture-chat", loaded)
            val finished = requireNotNull(AgentRecovery(directory).load("fixture-chat"))
            assertNull(FinalReply.pending(finished))
            assertEquals(5, finished.length()); assertEquals("tool", finished.getJSONObject(3).getString("role"))
            assertEquals("型号找到了，是fixture-device。", finished.getJSONObject(4).getString("content"))
            assertFalse(finished.toString().contains("Internal draft:"))
        } finally { directory.deleteRecursively() }
    }
}
