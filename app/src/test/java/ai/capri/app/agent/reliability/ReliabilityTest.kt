package ai.capri.app.agent.reliability

import ai.capri.app.*
import ai.capri.app.agent.*
import ai.capri.app.agent.delegation.*
import ai.capri.app.files.WorkspaceFileTools
import ai.capri.app.model.ModelWire
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ReliabilityTest {
    @get:Rule val temp=TemporaryFolder()
    private var now=1000000L
    private val events get()=AgentEvents(File(temp.root,"events")){now}
    private val jobs get()=AgentJobs(File(temp.root,"jobs"),events){now}
    private fun spec(key:String="one",trigger:String="manual",mode:String="analysis",background:Boolean=true)=JSONObject().put("action","create").put("request_key",key).put("goal","Review the declared file").put("mode",mode).put("trigger",trigger).put("allow_background",background).put("files",JSONArray(listOf("input.txt")))
    @Test fun checkpointLineageLinksActualTransitionsAndRetainsExplicitGaps() {
        val messages=JSONArray().put(JSONObject().put("role","system").put("content","secret rules")).put(JSONObject().put("role","user").put("content","keep data"))
        val first=events.transition("chat",messages,"checkpoint").getString("event_id")
        events.transition("chat",messages,"checkpoint");assertEquals(1,events.read("chat").getLong("latest"))
        val resumed=events.transition("chat",messages,"resume","entry");assertEquals(first,resumed.getString("parent_node"))
        val compacted=events.transition("chat",messages,"compaction","archive");assertEquals(resumed.getString("event_id"),compacted.getString("parent_node"))
        assertFalse(events.read("chat").toString().contains("secret rules"));assertFalse(events.read("chat").toString().contains("keep data"))
        repeat(520){events.emit("chat","phase_changed",JSONObject().put("state","THINKING"))}
        assertTrue(events.read("chat").getBoolean("gap"));assertEquals(64,events.read("chat").getJSONArray("events").length())
        assertThrows(IllegalArgumentException::class.java){events.emit("chat","tool_result",JSONObject().put("password","secret"))}
    }
    @Test fun lineageHashUsesTheSanitizedCheckpointActuallyWrittenToDisk() {
        val messages=JSONArray().put(JSONObject().put("role","system").put("content","private runtime rules"))
            .put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(JSONObject().put("id","private").put("function",JSONObject().put("name","profile_search").put("arguments","{}")))))
            .put(JSONObject().put("role","tool").put("tool_call_id","private").put("content","private profile result"))
        events.transition("chat",messages,"checkpoint")
        val stored=RecoveryMessages.sanitized(messages);events.transition("chat",stored,"checkpoint")
        assertEquals(1,events.read("chat").getLong("latest"));assertEquals(messages.getJSONObject(0).getJSONObject("_capri_lineage").getString("checkpoint_hash"),stored.getJSONObject(0).getJSONObject("_capri_lineage").getString("checkpoint_hash"))
    }
    @Test fun differentHistoryScopesNeverConsumeOneAnothersEventsOrJobs() {
        val branch="chat:history:00000000-0000-0000-0000-000000000001"
        jobs.create("chat",spec(),"model","entry");events.emit("chat","process_completed")
        assertEquals(0,jobs.list(branch).length());assertEquals(0,events.read(branch).getJSONArray("events").length())
        assertThrows(IllegalArgumentException::class.java){events.read("../chat")}
    }
    @Test fun createValidatesInputsAndReusesTheSameDurableIdentity() {
        val args=spec();JobContract.validate(args);val first=jobs.create("chat",args,"model","entry")
        assertNull(jobs.nextWakeAt()) // Manual jobs never schedule themselves merely because they are ready.
        assertEquals(first.getString("id"),jobs.create("chat",JSONObject(args.toString()),"model","entry").getString("id"))
        assertThrows(IllegalArgumentException::class.java){jobs.create("chat",spec().put("goal","another goal"),"model","entry")}
        for(input in listOf(spec("bad").put("files",JSONArray(listOf("../outside"))),spec("bad",mode="checkpoint"),spec("bad",trigger="interval").put("due_at",now).put("interval_ms",100))) {
            assertThrows(IllegalArgumentException::class.java){jobs.create("chat",input,"model","entry")}
        }
        assertThrows(IllegalArgumentException::class.java){JobContract.validate(JSONObject().put("action","cancel").put("id","x").put("goal","extra"))}
    }
    @Test fun onceAndRecurringTriggersCoalesceDowntimeAndStopAfterRepeatedFailures() {
        val task=jobs.create("chat",spec(trigger="interval").put("due_at",now+10).put("interval_ms",900000),"model","entry")
        assertTrue(jobs.due("chat").isEmpty());now+=100000000L
        assertEquals(1,jobs.due("chat").size)
        repeat(3) { index ->
            val claim=jobs.claim("chat",task.getString("id"),"model")
            assertEquals(index+1,claim.getInt("occurrence"))
            jobs.finish("chat",claim,JSONObject().put("status","partial"));now+=900000
            jobs.due("chat")
        }
        assertEquals("needs_review",jobs.read("chat",task.getString("id")).getString("state"));assertTrue(jobs.due("chat").isEmpty())
    }
    @Test fun eventSourceAndCursorPreventOldOrDuplicateTriggerDelivery() {
        events.emit("chat","process_completed",JSONObject().put("source_id","process"))
        val task=jobs.create("chat",spec(trigger="event").put("event_kind","process_completed").put("source_id","process"),"model","entry")
        assertTrue(jobs.due("chat").isEmpty());events.emit("chat","process_completed",JSONObject().put("source_id","other"));assertTrue(jobs.due("chat").isEmpty())
        events.emit("chat","process_completed",JSONObject().put("source_id","process"));assertEquals(1,jobs.due("chat").size)
        val claim=jobs.claim("chat",task.getString("id"),"model");jobs.finish("chat",claim,JSONObject().put("status","ok"));assertTrue(jobs.due("chat").isEmpty())
        val late=jobs.create("chat",spec("late",trigger="event").put("event_kind","coding_ready"),"model","entry")
        repeat(520){events.emit("chat","phase_changed")};jobs.due("chat");assertEquals("needs_review",jobs.read("chat",late.getString("id")).getString("state"))
    }
    @Test fun pausedAndCancelledOccurrencesRetainResultsWithoutRearming() {
        for(action in listOf("pause","cancel")) {
            val task=jobs.create("chat",spec(action),"model","entry");val claim=jobs.claim("chat",task.getString("id"),"model")
            jobs.change("chat",task.getString("id"),action);assertFalse(jobs.current("chat",claim))
            jobs.finish("chat",claim,JSONObject().put("status","partial"));assertEquals(if(action=="pause")"paused" else "cancelled",jobs.read("chat",task.getString("id")).getString("state"))
            assertTrue(jobs.due("chat").isEmpty())
        }
    }
    @Test fun stoppedPeriodicWorkIsNotRearmedAndCancellationCannotBeUndoneByPause() {
        val task=jobs.create("chat",spec(trigger="interval").put("due_at",now).put("interval_ms",900000),"model","entry");jobs.due("chat")
        val id=task.getString("id");val claim=jobs.claim("chat",id,"model")
        jobs.change("chat",id,"pause")
        assertThrows(IllegalArgumentException::class.java){jobs.change("chat",id,"resume")}
        jobs.finish("chat",claim,JSONObject().put("status","partial").put("error_code","job_interrupted"))
        jobs.change("chat",id,"resume");jobs.due("chat");val retry=jobs.claim("chat",id,"model")
        jobs.finish("chat",retry,JSONObject().put("status","partial").put("error_code","job_interrupted"));now+=9000000
        assertEquals("interrupted",jobs.read("chat",id).getString("state"));assertTrue(jobs.due("chat").isEmpty())
        jobs.change("chat",id,"cancel");jobs.change("chat",id,"pause");assertEquals("cancelled",jobs.read("chat",id).getString("state"))
    }
    @Test fun restartDoesNotReplayAnUnknownRunningOccurrence() {
        val task=jobs.create("chat",spec(),"model","entry");val id=task.getString("id")
        // A crash fixture is written before constructing a replacement scheduler, with no live claim.
        val file=File(temp.root,"jobs/chat/jobs.json");val saved=JSONObject(file.readText());saved.getJSONArray("jobs").getJSONObject(0).put("state","running").put("run_id","old");file.writeText(saved.toString())
        jobs.recover("chat");assertEquals("interrupted",jobs.read("chat",id).getString("state"));assertTrue(jobs.due("chat").isEmpty())
        jobs.change("chat",id,"run");assertEquals(1,jobs.due("chat").size)
    }
    @Test fun changedModelDestinationRequiresReviewAndDoesNotStartAnOccurrence() {
        val task=jobs.create("chat",spec(),"model","entry");assertEquals("needs_review",jobs.claim("chat",task.getString("id"),"other-model").getString("state"))
        assertEquals(0,jobs.read("chat",task.getString("id")).getInt("occurrence"))
    }
    @Test fun purposeBudgetsIsolateAuxiliaryFailureAndKeepTheParentRoute()=runBlocking<Unit> {
        val policy=ModelRequests(maximum=20,pause={},purposeLimits=mapOf(ModelRequests.Purpose.COMPRESSION to 2))
        try{policy.execute(ModelRequests.Purpose.COMPRESSION){throw IOException()};fail()}catch(_:ModelRequestFailure){}
        assertEquals(2,policy.used)
        try{policy.execute(ModelRequests.Purpose.COMPRESSION){fail("auxiliary budget must stop transport");JSONObject()};fail()}catch(_:ModelRequestFailure){}
        assertEquals("ok",policy.execute{JSONObject().put("status","ok")}.getString("status"))
        assertEquals(2,policy.report().getJSONObject("failures_by_purpose").getInt("compression"));assertEquals("same_config_only",policy.report().getString("fallback_policy"))
    }
    @Test fun appOwnedSymlinksAndCorruptStoresFailWithoutReplacingData() {
        val outside=File(temp.root,"outside.json").apply { writeText("{}") };val root=File(temp.root,"private").apply { mkdirs() }
        val target=File(root,"chat");java.nio.file.Files.createSymbolicLink(target.toPath(),temp.root.toPath())
        assertThrows(IllegalArgumentException::class.java){AgentEvents(root).emit("chat","turn_started")}
        target.delete();target.mkdirs();val store=File(target,"journal.json").apply { writeText("corrupt") }
        assertThrows(org.json.JSONException::class.java){AgentEvents(root).emit("chat","turn_started")}
        assertEquals("corrupt",store.readText());assertEquals("{}",outside.readText())
    }
    @Test fun cancelledRunningModelIsInterruptedAndItsOccurrenceIsNotReplayed()=runBlocking<Unit> {
        val workspace=Workspace(File(temp.root,"cancel-sessions"));workspace.file("chat","input.txt").apply { parentFile!!.mkdirs();writeText("keep") }
        val config=ModelConfig(model="fixture",key="test-key");val task=jobs.create("chat",spec(),ModelWire.scope(config),"entry")
        val runner=JobRunner(jobs,events,DelegateEngine(File(temp.root,"cancel-delegates"),WorkspaceFileTools(workspace)));val started=CompletableDeferred<Unit>();var cancelled=false
        val work=launch {
            runner.run("chat",task.getString("id"),config) { _,_,_,budget,gate ->
                budget.execute(ModelRequests.Purpose.DELEGATION,beforeAttempt=gate) { started.complete(Unit);try { awaitCancellation() } finally { cancelled=true } }
            }
        }
        started.await();jobs.change("chat",task.getString("id"),"cancel");withTimeout(3000){work.join()}
        assertTrue(cancelled);assertEquals("cancelled",jobs.read("chat",task.getString("id")).getString("state"));assertTrue(jobs.due("chat").isEmpty())
    }
    @Test fun realDelegateRunnerRetainsEvidenceAndRequiresExplicitForegroundRun()=runBlocking<Unit> {
        val workspace=Workspace(File(temp.root,"sessions"));workspace.file("chat","input.txt").apply { parentFile!!.mkdirs();writeText("original") }
        val config=ModelConfig(model="fixture",key="test-key");val task=jobs.create("chat",spec(background=false),ModelWire.scope(config),"entry")
        assertThrows(IllegalArgumentException::class.java){jobs.claim("chat",task.getString("id"),ModelWire.scope(config))}
        jobs.change("chat",task.getString("id"),"run")
        val runner=JobRunner(jobs,events,DelegateEngine(File(temp.root,"delegates"),WorkspaceFileTools(workspace)));var attempts=0
        val result=runner.run("chat",task.getString("id"),config,true) { _,tools,_,budget,gate ->
            val names=(0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
            assertFalse("terminal" in names);assertFalse("write_file" in names)
            budget.execute(ModelRequests.Purpose.DELEGATION,beforeAttempt=gate) {
                attempts++
                val f=if(attempts==1)JSONObject().put("name","read_file").put("arguments",JSONObject().put("path","input.txt").toString()) else JSONObject().put("name","delegate_finish").put("arguments",JSONObject().put("outcome","completed").put("summary","The file contains original").put("evidence",JSONArray(listOf("read"))).put("unresolved",JSONArray()).toString())
                JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(JSONObject().put("id",if(attempts==1)"read" else "finish").put("type","function").put("function",f))))))
            }
        }
        assertEquals(result.toString(),"ok",result.getString("status"));assertEquals(2,attempts);assertEquals("completed",jobs.read("chat",task.getString("id")).getString("state"));assertTrue(jobs.read("chat",task.getString("id")).has("last_result"))
        assertEquals("original",workspace.file("chat","input.txt").readText());assertTrue(jobs.due("chat").isEmpty())
    }
}
