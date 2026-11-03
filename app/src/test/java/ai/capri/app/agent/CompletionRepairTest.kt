package ai.capri.app.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompletionRepairTest {
    private fun messages(): JSONArray = JSONArray().put(JSONObject().put("role", "system").put("content", "rules")).also {
        TaskState.ensure(it, "Check public Skill sync status", false)
        it.put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "actual-call")
            .put("function", JSONObject().put("name", "terminal").put("arguments", "{}")))))
        it.put(JSONObject().put("role", "tool").put("tool_call_id", "actual-call")
            .put("content", """{"state":"completed","exit_code":0,"command_id":"execution-id","output":"Checked existing status"}"""))
    }
    @Test fun schemaSeparatesActionsAndKeepsProviderContracts() {
        val spec = TaskState.specification()
        val schema = spec.getJSONObject("function").getJSONObject("parameters")
        val finish = JSONObject().put("action", "finish").put("outcome", "partial").put("summary", "Unverified").put("evidence", JSONArray())
        ToolInput.check(finish, schema)
        ToolInput.check(JSONObject().put("action", "read"), schema)
        ToolInput.check(JSONObject().put("action", "update").put("findings", "Checked status"), schema)
        for (invalid in listOf(JSONObject(finish.toString()).put("steps", JSONArray()),
            JSONObject().put("action", "update"), JSONObject().put("action", "read").put("evidence", JSONArray()))) {
            try { ToolInput.check(invalid, schema); fail("Action fields must be rejected") } catch (_: IllegalArgumentException) {}
        }
        for (protocol in listOf("CHAT", "RESPONSES", "ANTHROPIC", "GEMINI")) {
            val wire = ai.capri.app.model.ModelWire.request(ai.capri.app.ModelConfig(protocol = protocol, model = "fixture"),
                JSONArray().put(JSONObject().put("role", "user").put("content", "Check status")), JSONArray().put(spec), 1024)
            assertTrue(protocol, wire.toString().contains("oneOf"))
        }
    }
    @Test fun finishErrorsExposeCorrectReferencesWithoutChangingState() {
        val messages = messages()
        val before = TaskState.snapshot(messages).toString()
        val args = JSONObject().put("action", "finish").put("outcome", "completed").put("summary", "Status checked")
            .put("evidence", JSONArray().put("execution-id"))
        for (extra in listOf(true, false)) {
            if (extra) args.put("steps", JSONArray()) else args.remove("steps")
            try { TaskState.execute(messages, args); fail("Must reject malformed finish") }
            catch (error: IllegalArgumentException) {
                assertTrue(error.message!!.contains("actual-call"))
                assertTrue(error.message!!.contains("tool_call_id"))
            }
            assertEquals(before, TaskState.snapshot(messages).toString())
        }
        args.put("evidence", JSONArray().put("actual-call"))
        TaskState.execute(messages, args)
        assertNull(CompletionEvidence.issue(messages))
        assertTrue(TaskState.message(messages).toString().contains("work_evidence"))
    }
    @Test fun partialDeliverySurvivesRecoveryAndDisablesExactSuccessBypass() {
        val messages = messages()
        TaskState.execute(messages, JSONObject().put("action", "finish").put("outcome", "partial")
            .put("summary", "Completion remains unverified").put("evidence", JSONArray()))
        val draft = "Only existing evidence can establish the result; work remains partial."
        messages.put(JSONObject().put("role", "assistant").put("content", draft))
        val state = ai.capri.app.FinalReply.mark(messages, "Check public Skill sync status", draft, setOf("actual-call"))
        val restored = ai.capri.app.RecoveryMessages.forResume(messages)
        assertEquals("partial", TaskState.snapshot(restored).getJSONObject("conclusion").getString("outcome"))
        assertNotNull(ai.capri.app.FinalReply.pending(restored))
        val input = ai.capri.app.FinalReply.messages("core", ai.capri.app.SessionKind.TASK, "now", "", state,
            restored, ai.capri.app.model.ModelCapacity(32768, 4096, "fixture").budget())
        assertTrue(input.toString().contains("Checked existing status"))
        assertTrue(input.toString().contains("Completion remains unverified"))
        assertNull(DeliveryPolicy.exactJson(state, restored))
    }
    @Test fun evidenceDirectoryIsBoundedAndPreservesFailureClassification() {
        val messages = messages()
        repeat(25) { i ->
            messages.put(JSONObject().put("role", "tool").put("tool_call_id", "archived-$i").put("content", """{"status":"error"}"""))
        }
        val directory = CompletionEvidence.directory(messages)
        assertEquals(26, directory.getInt("total"))
        assertEquals(16, directory.getJSONArray("results").length())
        assertEquals(10, directory.getInt("omitted"))
        assertEquals("failed_or_unresolved", directory.getJSONArray("results").getJSONObject(0).getString("assessment"))
    }
    @Test fun repeatedCompletionFailureDeliversPartialWithoutReplayingWork() = runBlocking {
        val messages = messages()
        var used = 0; var partial = 0; var executions = 0; var checkpoint = ""
        val phases = mutableListOf<TurnState.Phase>()
        val host = object : AgentLoop.Host {
            override val used get() = used
            override val maximum = 6
            override val request = "Check public Skill sync status"
            override fun phase(value: AgentLoop.Phase) { phases += TurnState.Phase.valueOf(value.name) }
            override suspend fun prepare() = AgentLoop.Input(messages, JSONArray(), 1024)
            override suspend fun infer(input: AgentLoop.Input): JSONObject {
                used++
                return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                    .put("message", JSONObject().put("role", "assistant").put("content", "Everything completed"))))
            }
            override suspend fun rebudget(error: ai.capri.app.model.ModelContextRejected, input: AgentLoop.Input) {}
            override suspend fun observe(input: AgentLoop.Input, response: JSONObject) {}
            override fun append(message: JSONObject) { messages.put(message) }
            override suspend fun checkpoint() { checkpoint = messages.toString() }
            override fun decision(choice: JSONObject, count: Int, decision: String, corrections: Int) {}
            override fun preamble(content: String, tools: List<String>) {}
            override fun status(message: String) {}
            override suspend fun execute(call: JSONObject): String { executions++; return "{}" }
            override suspend fun record(call: JSONObject, result: String) { fail("No new work expected") }
            override fun completionIssue() = CompletionEvidence.issue(messages)
            override suspend fun deliver(draft: String) { fail("Unverified draft must not be delivered") }
            override suspend fun deliverIncomplete(reason: String) {
                partial++
                assertTrue(reason.contains("actual-call"))
                assertTrue(reason.contains("partial"))
            }
        }
        assertEquals(AgentLoop.Outcome.INCOMPLETE, AgentLoop(host).run())
        assertEquals(2, used); assertEquals(1, partial); assertEquals(0, executions)
        assertEquals(TurnState.Phase.INCOMPLETE, phases.last())
        assertTrue(checkpoint.contains("Checked existing status"))
    }
}
