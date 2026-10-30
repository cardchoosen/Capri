package ai.capri.app.agent

import ai.capri.app.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TurnControlTest {
    private fun messages() = JSONArray().put(JSONObject().put("role", "system").put("content", "rules"))
    private fun call(id: String, name: String, args: String = "{}") = JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args))
    private fun reply(text: String, calls: JSONArray = JSONArray()) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", text).put("tool_calls", calls))))
    private fun work(m: JSONArray, id: String, result: String, name: String = "write_file") {
        m.put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(call(id, name))))
        m.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", result))
    }
    private fun reject(block: () -> Unit) { try { block(); fail("Invalid control update was accepted") } catch (_: IllegalArgumentException) {} }
    private open class Host(val responses: List<JSONObject>) : AgentLoop.Host {
        override var used = 0; override val maximum = 10; override val request = "Inspect the file"
        val m = JSONArray().put(JSONObject().put("role", "system").put("content", "rules"))
        val phases = mutableListOf<TurnState.Phase>(); val executed = mutableListOf<String>(); val delivered = mutableListOf<String>()
        var checkpoint = ""; var queued = false; var wait = false
        override fun phase(value: TurnState.Phase) { phases += value }
        override suspend fun acceptInput(): Boolean = queued.also { if (it) { queued = false; append(JSONObject().put("role", "user").put("content", "Use the corrected path")) } }
        override fun canExecute() = !queued && !wait
        override fun waiting() = wait
        override suspend fun prepare() = AgentLoop.Input(m, JSONArray(), 1024)
        override suspend fun infer(input: AgentLoop.Input) = responses[used++]
        override suspend fun rebudget(error: ai.capri.app.model.ModelContextRejected, input: AgentLoop.Input) {}
        override suspend fun observe(input: AgentLoop.Input, response: JSONObject) {}
        override fun append(message: JSONObject) { m.put(message) }
        override suspend fun checkpoint() { checkpoint = m.toString() }
        override fun decision(choice: JSONObject, count: Int, decision: String, corrections: Int) {}
        override fun preamble(content: String, tools: List<String>) {}
        override fun status(message: String) {}
        override suspend fun execute(call: JSONObject): String { executed += call.getString("id");return "{}" }
        override suspend fun record(call: JSONObject, result: String) { m.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", result));checkpoint() }
        override suspend fun deliver(draft: String) { delivered += draft }
    }
    @Test fun correctionDuringInferenceDiscardsStaleActionsBeforeDispatch() = runBlocking {
        val host = object : Host(listOf(reply("", JSONArray().put(call("stale", "write_file"))), reply("Corrected answer"))) {
            override suspend fun infer(input: AgentLoop.Input): JSONObject = super.infer(input).also { if (used == 1) queued = true }
        }
        assertEquals(AgentLoop.Outcome.COMPLETE, AgentLoop(host).run())
        assertTrue(host.executed.isEmpty()); assertFalse(host.m.toString().contains("stale"))
        assertEquals(listOf("Corrected answer"), host.delivered)
        assertTrue(host.phases.containsAll(listOf(TurnState.Phase.PREPARING, TurnState.Phase.THINKING, TurnState.Phase.DELIVERING, TurnState.Phase.COMPLETED)))
    }
    @Test fun correctionBetweenWritesRecordsNonDispatchInsteadOfReplayingBatch() = runBlocking {
        val host = object : Host(listOf(reply("", JSONArray().put(call("first", "write_file")).put(call("obsolete", "write_file"))), reply("Updated"))) {
            override suspend fun execute(call: JSONObject) = super.execute(call).also { if (call.getString("id") == "first") queued = true }
        }
        assertEquals(AgentLoop.Outcome.COMPLETE, AgentLoop(host).run())
        assertEquals(listOf("first"), host.executed)
        assertTrue(host.checkpoint.contains("not_executed")); assertTrue(host.checkpoint.contains("obsolete"))
    }
    @Test fun clarificationPausesBeforeNextActionAndDoesNotDeliverFinal() = runBlocking {
        val host = object : Host(listOf(reply("", JSONArray().put(call("ask", "ask_user")).put(call("write", "write_file"))))) {
            override suspend fun execute(call: JSONObject) = super.execute(call).also { wait = true }
        }
        assertEquals(AgentLoop.Outcome.WAITING, AgentLoop(host).run())
        assertEquals(listOf("ask"), host.executed); assertTrue(host.delivered.isEmpty())
        assertEquals(TurnState.Phase.WAITING, host.phases.last()); assertTrue(host.checkpoint.contains("not_executed"))
    }
    @Test fun cancellationPersistsCancelledPhaseWithoutDelivering() = runBlocking {
        val host = object : Host(emptyList()) { override suspend fun infer(input: AgentLoop.Input): JSONObject = throw CancellationException() }
        try { AgentLoop(host).run();fail() } catch (_: CancellationException) {}
        assertEquals(TurnState.Phase.CANCELLED, host.phases.last()); assertTrue(host.checkpoint.isNotBlank()); assertTrue(host.delivered.isEmpty())
    }
    @Test fun lateCorrectionDuringDeliveryReturnsToExecutionLoop() = runBlocking {
        val host = object : Host(listOf(reply("Old draft"), reply("Corrected draft"))) {
            override suspend fun deliver(draft: String) { if (used == 1) { queued = true; throw TurnState.Redirect() };super.deliver(draft) }
        }
        assertEquals(AgentLoop.Outcome.COMPLETE, AgentLoop(host).run());assertEquals(listOf("Corrected draft"),host.delivered)
    }
    @Test fun steeringAndClarificationSurviveStorageWithoutDuplicateInsertion() {
        val original = Entry("user", "Create a report and keep existing files")
        val change = Entry("user", "Use text format", control = "answer")
        val m = messages();TaskState.ensure(m, original.text, false);TurnState.ensure(m, original, false)
        TurnState.waitForUser(m, JSONObject().put("question", "Which format?").put("options", JSONArray(listOf("Text", "JSON"))))
        val restored = JSONArray(RecoveryMessages.sanitized(m).toString())
        val session = Session(messages = mutableListOf(original, change))
        val entries = SessionCatalog.decode(SessionCatalog.encode(listOf(session))).single { it.id == session.id }.messages
        assertEquals("answer", entries.last().control); assertTrue(TurnState.apply(restored, entries));assertFalse(TurnState.apply(restored, entries))
        assertNull(TurnState.question(restored));assertEquals(original.text, TaskState.snapshot(restored).getString("request"))
        assertTrue(TaskState.snapshot(restored).getJSONArray("user_context").toString().contains(change.text))
        assertTrue(TurnState.pending(JSONArray(restored.toString()),entries).isEmpty())
    }
    @Test fun invalidQuestionDoesNotReplaceSavedQuestion() {
        val m = messages();TurnState.ensure(m,Entry("user","request"),false)
        TurnState.waitForUser(m,JSONObject().put("question","Which file?"));val before=m.toString()
        reject { TurnState.waitForUser(m,JSONObject().put("question"," ")) };assertEquals(before,m.toString())
        reject { TurnState.waitForUser(m,JSONObject().put("question","Which?").put("options",JSONArray().put("Only one"))) };assertEquals(before,m.toString())
    }
    @Test fun retriesAndCompressionCannotConsumeDeliveryReservation() = runBlocking {
        val budget=ModelRequests(maximum=4,pause={},reservedDelivery=1);var attempts=0
        budget.execute { if(attempts++==0)throw IOException();JSONObject() }
        budget.execute(purpose=ModelRequests.Purpose.COMPRESSION) { JSONObject() }
        try { budget.execute { fail("Execution must leave delivery capacity");JSONObject() };fail() } catch (_:ModelRequestFailure) {}
        budget.execute(purpose=ModelRequests.Purpose.DELIVERY) { JSONObject() }
        val r=budget.report();assertEquals(4,r.getInt("used"));assertEquals(1,r.getInt("retries"))
        assertEquals(2,r.getJSONObject("attempts_by_purpose").getInt("execution"));assertEquals(1,r.getJSONObject("attempts_by_purpose").getInt("compression"));assertEquals(1,r.getJSONObject("attempts_by_purpose").getInt("delivery"))
    }
    @Test fun plansAndFailedOrInventedEvidenceCannotConcludeCompleted() {
        val m=messages();TaskState.ensure(m,"Create report",false)
        work(m,"failure","{\"status\":\"ok\",\"data\":{\"command\":{\"state\":\"failed\",\"exit_code\":1}}}")
        val finish=JSONObject().put("action","finish").put("outcome","completed").put("summary","Done").put("evidence",JSONArray().put("invented"))
        val before=TaskState.snapshot(m).toString();reject { TaskState.execute(m,finish) };assertEquals(before,TaskState.snapshot(m).toString())
        finish.put("evidence",JSONArray().put("failure"));reject { TaskState.execute(m,finish) }
        finish.put("outcome","blocked");TaskState.execute(m,finish);assertEquals("blocked",TaskState.snapshot(m).getJSONObject("conclusion").getString("outcome"))
    }
    @Test fun completionAssessmentExpiresAfterNewWorkOrUserCorrection() {
        val m=messages();val origin=Entry("user","Create file");TaskState.ensure(m,origin.text,false);TurnState.ensure(m,origin,false)
        work(m,"write","{\"status\":\"ok\"}")
        assertNotNull(CompletionEvidence.issue(m))
        TaskState.execute(m,JSONObject().put("action","finish").put("outcome","completed").put("summary","File created and checked").put("evidence",JSONArray().put("write")))
        assertNull(CompletionEvidence.issue(m))
        work(m,"new-work","{\"status\":\"error\"}");assertNotNull(CompletionEvidence.issue(m))
        TurnState.apply(m,listOf(origin,Entry("user","Use the other filename",control="steering")))
        assertFalse(TaskState.snapshot(m).has("conclusion"))
    }
    @Test fun explicitActionCannotBeCompletedWithoutWorkButPlanningNeedsNoInventedAction() {
        val m=messages();TaskState.ensure(m,"请修改 config.json",false)
        assertNotNull(CompletionEvidence.issue(m))
        reject { TaskState.execute(m,JSONObject().put("action","finish").put("outcome","completed").put("summary","Updated").put("evidence",JSONArray())) }
        TaskState.execute(m,JSONObject().put("action","finish").put("outcome","blocked").put("summary","Required file is unavailable").put("evidence",JSONArray()))
        assertNull(CompletionEvidence.issue(m))
        val plan=messages();TaskState.ensure(plan,"只给方案，不要执行",false)
        assertNull(CompletionEvidence.issue(plan))
        for (text in listOf("Write a haiku", "Create a story", "Write a plan", "写一首诗", "修改这句话", "Create a character", "Fix this sentence")) {
            val answer=messages();TaskState.ensure(answer,text,false);assertNull(text,CompletionEvidence.issue(answer))
        }
    }
    @Test fun oldTaskEvidenceCannotProveNewTaskCompletion() {
        val m=messages();work(m,"old","{\"status\":\"ok\"}")
        TaskState.ensure(m,"New task",false);TurnState.ensure(m,Entry("user","New task"),false)
        reject { TaskState.execute(m,JSONObject().put("action","finish").put("outcome","completed").put("summary","Done").put("evidence",JSONArray().put("old"))) }
        assertFalse(CompletionEvidence.requiresConclusion(m))
    }
    @Test fun repeatedNoProgressRunStopsIncompleteAndRetainsRecordedResults() = runBlocking {
        val host = Host((1..6).map { reply("",JSONArray().put(call("read-$it","read_file"))) })
        assertEquals(AgentLoop.Outcome.INCOMPLETE,AgentLoop(host).run())
        assertEquals(6,host.executed.size);assertTrue(host.delivered.isEmpty());assertEquals(TurnState.Phase.INCOMPLETE,host.phases.last())
        assertTrue(host.checkpoint.contains("read-6"))
    }
    @Test fun missingCrashResultIsNotSuccessfulEvidence() {
        val m=messages();TaskState.ensure(m,"Run work",false)
        m.put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(call("uncertain","write_file"))))
        val recovered=RecoveryMessages.forResume(m)
        reject { TaskState.execute(recovered,JSONObject().put("action","finish").put("outcome","completed").put("summary","Done").put("evidence",JSONArray().put("uncertain"))) }
    }
    @Test fun equivalentCallsProduceTypedFeedbackAndStallSurvivesCheckpoint() {
        val guard=ProgressGuard();val c=call("a","read_file","{\"path\":\"note.txt\",\"offset\":0}")
        repeat(2){assertNull(guard.observe(c,"{\"status\":\"error\"}"))}
        val reordered=call("b","read_file","{\"offset\":0,\"path\":\"note.txt\"}")
        assertTrue(guard.observe(reordered,"{\"status\":\"error\"}")!!.contains("repeated_failure"))
        val restored=ProgressGuard(JSONObject(guard.snapshot().toString()));repeat(3){restored.observe(c,"{\"status\":\"error\"}")}
        assertTrue(restored.stalled);restored.reset();assertFalse(restored.stalled)
        val poll=call("poll","terminal","{\"action\":\"read\",\"offset\":10}");repeat(2){restored.observe(poll,"{\"state\":\"running\"}")}
        assertTrue(restored.observe(poll,"{\"state\":\"running\"}")!!.contains("unchanged_poll"))
    }
}
