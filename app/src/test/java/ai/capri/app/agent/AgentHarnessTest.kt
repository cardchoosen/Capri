package ai.capri.app.agent

import ai.capri.app.RecoveryMessages
import ai.capri.app.FinalReply
import ai.capri.app.SessionKind
import ai.capri.app.knowledge.ContextHistory
import ai.capri.app.model.ContextBudget
import ai.capri.app.model.ContextTokens
import ai.capri.app.ModelConfig
import ai.capri.app.model.ModelWire
import ai.capri.app.model.ModelCapacity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AgentHarnessTest {
    private fun call(id: String, name: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", "{}"))
    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)
    private fun response(text: String, calls: JSONArray = JSONArray(), finish: String = "stop") = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", finish).put("message", msg("assistant", text).put("tool_calls", calls))))
    private open class Host(val responses: List<JSONObject>) : AgentLoop.Host {
        override var used = 0
        override val maximum = 8
        override val request = "Create the requested file"
        val history = JSONArray().put(JSONObject().put("role", "system").put("content", "rules"))
        val executed = mutableListOf<String>(); val delivered = mutableListOf<String>()
        var saved = ""; var lastStatus = ""
        override suspend fun prepare() = AgentLoop.Input(history, JSONArray(), 1024)
        override suspend fun infer(input: AgentLoop.Input): JSONObject = responses[used++]
        override suspend fun rebudget(error: ai.capri.app.model.ModelContextRejected, input: AgentLoop.Input) {}
        override suspend fun observe(input: AgentLoop.Input, response: JSONObject) {}
        override fun append(message: JSONObject) { history.put(message) }
        override suspend fun checkpoint() { saved = history.toString() }
        override fun decision(choice: JSONObject, count: Int, decision: String, corrections: Int) {}
        override fun preamble(content: String, tools: List<String>) {}
        override fun status(message: String) { lastStatus = message }
        override suspend fun execute(call: JSONObject): String {
            assertTrue(saved.contains(call.getString("id")))
            executed += call.getString("id"); return "done"
        }
        override suspend fun record(call: JSONObject, result: String) {
            history.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", result)); checkpoint()
        }
        override suspend fun deliver(draft: String) { delivered += draft }
    }
    @Test fun executionPersistsIntentAndResultsWithoutReplayingSuccessfulWrites() = runBlocking {
        val host = Host(listOf(response("", JSONArray().put(call("write1", "write_file"))), response("File created")))
        assertEquals(AgentLoop.Outcome.COMPLETE, AgentLoop(host).run())
        assertEquals(listOf("write1"), host.executed)
        assertEquals(listOf("File created"), host.delivered)
        val restored = RecoveryMessages.forResume(JSONArray(host.saved))
        assertTrue(restored.toString().contains("done"))
        assertFalse(restored.toString().contains("结果未记录"))
    }
    @Test fun truncatedOrDuplicateCallsNeverExecute() = runBlocking {
        val truncated = Host(listOf(response("partial", JSONArray().put(call("a", "write_file")), "length")))
        assertEquals(AgentLoop.Outcome.BUDGET, AgentLoop(truncated).run())
        assertTrue(truncated.executed.isEmpty())
        val duplicate = Host(listOf(response("", JSONArray().put(call("a", "write_file")).put(call("a", "write_file")))))
        try { AgentLoop(duplicate).run(); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(duplicate.executed.isEmpty())
    }
    @Test fun safeReadsOverlapButWritesFormBarriersAndFinishedResultsSurviveCancellation() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>(); val committed = mutableListOf<String>()
        val calls = JSONArray().put(call("r1", "read_file")).put(call("r2", "read_file")).put(call("w", "write_file")).put(call("r3", "read_file"))
        withTimeout(5000) {
            val job = launch { ToolScheduler(2).run(calls, { c ->
                val id = c.getString("id"); events += "start:$id"
                if (id == "r1") { started.complete(Unit); release.await() }
                if (id == "r2") { started.await(); release.complete(Unit) }
                if (id == "w") assertEquals(setOf("r1", "r2"), committed.toSet())
                if (id == "r3") assertTrue("w" in committed)
                id
            }, { c, _ -> committed += c.getString("id") }) }
            job.join()
        }
        assertEquals(4, committed.size)
        val firstCommitted = CompletableDeferred<Unit>(); val finished = mutableListOf<String>()
        val job = launch { ToolScheduler().run(JSONArray().put(call("fast", "read_file")).put(call("slow", "read_file")), {
            if (it.getString("id") == "slow") awaitCancellation()
            "ok"
        }, { c, _ -> finished += c.getString("id"); firstCommitted.complete(Unit) }) }
        withTimeout(5000) { firstCommitted.await() }
        job.cancelAndJoin()
        assertEquals(listOf("fast"), finished)
        assertFalse(ToolScheduler.parallelSafe(call("unknown", "mcp_external")))
    }
    @Test fun discoveryLoadsOnlyRequestedSchemasAndRevokesUnavailableTools() {
        fun spec(name: String) = TaskState.spec(name, "Query Android device networking", JSONObject(), emptyList())
        val all = JSONArray().put(spec("read_file")).put(spec("android")).put(spec("mcp_device"))
        val discovery = ToolDiscovery()
        val first = discovery.assemble(all)
        assertTrue(first.toString().contains("\"name\":\"android\""))
        assertTrue(discovery.manifest().toString().contains("Android"))
        assertTrue(discovery.allowed("android"))
        val found = JSONObject(discovery.execute("tool_search", JSONObject().put("query", "android").put("limit", 1)))
        assertEquals("android", found.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        assertTrue(discovery.assemble(all).toString().contains("\"name\":\"android\""))
        assertTrue(discovery.allowed("android"))
        discovery.assemble(JSONArray().put(spec("read_file")))
        assertFalse(discovery.allowed("android"))
        assertEquals("partial", JSONObject(discovery.execute("tool_describe", JSONObject().put("names", JSONArray().put("android")))).getString("status"))
        assertTrue(ContextTokens.text(first.toString()) < ContextTokens.text(all.toString()) + 1600)
    }
    @Test fun taskStateSurvivesRecoveryButNeverLeaksPrivateMetadataToProvider() {
        val messages = JSONArray().put(msg("system", "rules")).put(msg("user", "Keep the public API"))
        TaskState.ensure(messages, "Fix the implementation", false)
        val steps = JSONArray().put(JSONObject().put("id", "fix").put("text", "Fix implementation").put("status", "in_progress"))
        TaskState.execute(messages, JSONObject().put("action", "update").put("steps", steps).put("findings", "Test currently fails"))
        val restored = RecoveryMessages.forResume(RecoveryMessages.sanitized(messages))
        TaskState.ensure(restored, "continue", true)
        assertEquals("Fix the implementation", TaskState.snapshot(restored).getString("request"))
        assertTrue(TaskState.snapshot(restored).toString().contains("Keep the public API"))
        for (protocol in listOf("CHAT", "RESPONSES", "ANTHROPIC", "GEMINI")) {
            assertFalse(ModelWire.request(ModelConfig(protocol = protocol, model = "fixture"), messages, JSONArray(), 1024).toString().contains(TaskState.KEY))
        }
        try { TaskState.execute(messages, JSONObject().put("action", "update").put("expected_revision", 0).put("findings", "bad")); fail() } catch (_: IllegalArgumentException) {}
        assertEquals("Test currently fails", TaskState.snapshot(messages).getString("findings"))
        messages.put(msg("assistant", "Internal answer"))
        val pending = FinalReply.mark(messages, "Fix it", "Internal answer")
        val finalInput = FinalReply.messages("core", SessionKind.TASK, "now", "", pending, messages, ModelCapacity(32768, 4096, "fixture").budget())
        assertTrue(finalInput.toString().contains("Keep the public API"))
    }
    @Test fun offloadedResultsRemainRecoverableAndCompactionUsesStructuredHandoff() = runBlocking {
        val root = Files.createTempDirectory("capri-harness").toFile()
        try {
            val context = ContextHistory(root, { it.readText() }, { f, value -> f.writeText(value) })
            val original = JSONObject().put("status", "error").put("output", "data".repeat(12000) + "FINAL FAILURE").toString()
            val bounded = JSONObject(context.boundResult("chat", "terminal", original))
            assertEquals("error", bounded.getString("status"))
            assertTrue(bounded.getString("preview_tail").contains("FINAL FAILURE"))
            val ref = bounded.getString("reference")
            val page = JSONObject(context.query("chat", emptyList(), JSONObject().put("reference", ref)))
            assertTrue(page.getInt("total_chars") > 40000)
            assertTrue(JSONObject(context.boundResult("other", "terminal", original)).getString("reference") != ref)
            try { context.query("other", emptyList(), JSONObject().put("reference", ref)); fail() } catch (_: java.io.FileNotFoundException) {}
            val messages = JSONArray().put(msg("system", "rules")).put(msg("user", "Preserve public API"))
            repeat(30) { messages.put(msg("assistant", "old material".repeat(120))) }
            messages.put(msg("user", "Fix failure"))
            var summaries = 0
            val budget = ContextBudget(8192, 1024, 300, "fixture")
            val compact = context.prepare("chat", messages, JSONArray(), budget) { input, _ ->
                summaries++; assertTrue(input.length() > 0); "Goal: fix failure. Constraint: preserve public API. Next: run targeted test."
            }
            assertEquals(1, summaries)
            assertTrue(budget.fits(ContextTokens.estimate(compact, JSONArray())))
            assertTrue(compact.toString().contains("Next: run targeted test"))
            assertTrue(compact.toString().contains("Preserve public API"))
            assertEquals("Fix failure", compact.getJSONObject(compact.length() - 1).getString("content"))
        } finally { root.deleteRecursively() }
    }
    @Test fun repeatedCallIdCannotOverwriteRecoveryEvidence() = runBlocking {
        val host = Host(listOf(response("", JSONArray().put(call("same", "write_file"))), response("", JSONArray().put(call("same", "write_file")))))
        try { AgentLoop(host).run(); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(listOf("same"), host.executed)
        assertTrue(host.saved.contains("done"))
    }
    @Test fun pendingPlanGetsReconciledBeforeDeliveryAndBudgetReservesFinalRequest() = runBlocking {
        var pending = true
        val host = object : Host(listOf(response("Draft"), response("", JSONArray().put(call("plan", "task_state"))), response("Verified answer"))) {
            override fun completionIssue(): String? = if (pending) "Reconcile the plan" else null
            override suspend fun execute(call: JSONObject): String { pending = false; return super.execute(call) }
        }
        assertEquals(AgentLoop.Outcome.COMPLETE, AgentLoop(host).run())
        assertEquals(listOf("Verified answer"), host.delivered)
        val small = object : Host(emptyList()) { override val maximum = 1 }
        assertEquals(AgentLoop.Outcome.BUDGET, AgentLoop(small).run())
        assertEquals(0, small.used)
    }
    @Test fun repeatedResultsWarnWithoutSuppressingOperations() {
        val guard = ProgressGuard()
        assertNull(guard.observe(call("1", "read_file"), "same"))
        assertNull(guard.observe(call("2", "read_file"), "same"))
        assertTrue(guard.observe(call("3", "read_file"), "same")!!.contains("No operation was skipped"))
        assertNull(guard.observe(call("4", "read_file"), "changed"))
    }
    @Test fun failedCompactionDoesNotMutateActiveTranscript() = runBlocking {
        val root = Files.createTempDirectory("capri-summary-failure").toFile()
        try {
            val context = ContextHistory(root, { it.readText() }, { f, v -> f.writeText(v) })
            val messages = JSONArray().put(msg("system", "rules")).put(msg("user", "Original goal"))
            repeat(30) { messages.put(msg("assistant", "material".repeat(200))) }
            messages.put(msg("user", "Latest correction"))
            val before = messages.toString()
            try { context.prepare("chat", messages, JSONArray(), ContextBudget(8192, 1024, 300, "fixture")) { _, _ -> "" }; fail() }
            catch (_: IllegalArgumentException) {}
            assertEquals(before, messages.toString())
        } finally { root.deleteRecursively() }
    }
    @Test fun earlyFailureAndNewestResultBothReachFinalDelivery() {
        val history = JSONArray().put(msg("system", "rules")).put(msg("user", "Do the task"))
        val ids = mutableSetOf<String>()
        repeat(15) { i ->
            ids += "e$i"
            history.put(JSONObject().put("role", "tool").put("tool_call_id", "e$i").put("content", if (i == 0) "{\"status\":\"error\",\"message\":\"EARLY_FAILURE\"}" else "success-$i"))
        }
        history.put(msg("assistant", "Internal answer"))
        val state = FinalReply.mark(history, "Do the task", "Internal answer", ids)
        val input = FinalReply.messages("core", SessionKind.TASK, "now", "", state, history, ModelCapacity(32768, 4096, "fixture").budget())
        assertTrue(input.toString().contains("EARLY_FAILURE"))
        assertTrue(input.toString().contains("success-14"))
    }

}
