package ai.capri.app.agent.delegation

import ai.capri.app.*
import ai.capri.app.agent.*
import ai.capri.app.files.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DelegationTest {
    @get:Rule val temp=TemporaryFolder()
    private val workspace get()=Workspace(File(temp.root,"sessions"))
    private fun parent():JSONArray=JSONArray().put(JSONObject().put("role","system").put("content","PRIVATE PERSONA NOT FOR CHILD")).also { TaskState.ensure(it,"Inspect declared code and preserve current user edits",false) }
    private fun spec(id:String="one",write:Boolean=false)=JSONObject().put("id",id).put("role",if(write)"coder" else "reviewer").put("goal","Inspect $id").put("files",JSONArray(listOf("$id.txt"))).apply { if(write)put("allow_write",true).put("write_paths",JSONArray(listOf("$id.txt"))) }
    private fun args(vararg tasks:JSONObject,key:String="request",parallel:Boolean=true)=JSONObject().put("action","run").put("request_key",key).put("reason","independent_review").put("tasks",JSONArray(tasks.toList())).put("parallel",parallel)
    private fun rootFile(name:String,text:String="original")=workspace.file("chat",name).apply { parentFile!!.mkdirs();writeText(text) }
    private fun function(id:String,name:String,args:JSONObject)=JSONObject().put("id",id).put("type","function").put("function",JSONObject().put("name",name).put("arguments",args.toString()))
    private fun response(vararg calls:JSONObject)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","tool_calls").put("message",JSONObject().put("role","assistant").put("content","").put("tool_calls",JSONArray(calls.toList())))))
    private fun finished(ref:String="read")=function("finish","delegate_finish",JSONObject().put("outcome","completed").put("summary","Reviewed the declared file").put("evidence",JSONArray(listOf(ref))).put("unresolved",JSONArray()))
    private open inner class Host(private val write:Boolean=false,private val delayMs:Long=0):DelegateEngine.Host {
        val calls=AtomicInteger();val concurrent=AtomicInteger();val peak=AtomicInteger();val prompts=mutableListOf<String>();val steps=ConcurrentHashMap<String,Int>()
        @Volatile var proceed=true
        override suspend fun canProceed()=proceed
        override suspend fun infer(messages:JSONArray,tools:JSONArray,output:Int,beforeAttempt:()->Unit):JSONObject {
            beforeAttempt();calls.incrementAndGet();val now=concurrent.incrementAndGet();peak.updateAndGet { maxOf(it,now) }
            val goal=JSONObject(messages.getJSONObject(1).getString("content")).getString("goal");val id=goal.substringAfterLast(' ');val step=steps.merge(goal,1,Int::plus)!!
            synchronized(prompts) { prompts+=messages.toString();assertFalse(messages.toString().contains("PRIVATE PERSONA NOT FOR CHILD"));assertFalse(tools.toString().contains("\"name\":\"terminal\"")) }
            try {
                if(delayMs>0)delay(delayMs)
                return when {
                    step==1 -> response(function("read","read_file",JSONObject().put("path","$id.txt").put("max_lines",10)))
                    write && step==2 -> response(function("write","write_file",JSONObject().put("path","$id.txt").put("content","proposed-$id")))
                    else -> response(finished(if(write)"write" else "read"))
                }
            } finally { concurrent.decrementAndGet() }
        }
    }
    @Test fun childContextIsMinimalAndParallelResultsCarryProvenance()=runBlocking<Unit> {
        rootFile("one.txt");rootFile("two.txt");rootFile("unrelated.txt","secret unrelated text")
        val engine=DelegateEngine(File(temp.root,"delegates"),WorkspaceFileTools(workspace));val host=Host(delayMs=20)
        val result=JSONObject(engine.execute("chat",args(spec(),spec("two")),parent(),DelegateBudget(),host))
        assertEquals("ok",result.getString("status"));assertEquals(2,host.peak.get());assertEquals(4,host.calls.get())
        assertFalse(host.prompts.joinToString().contains("secret unrelated text"));assertFalse(result.getBoolean("has_unmerged_proposals"))
        val read=JSONObject(engine.execute("chat",JSONObject().put("action","read").put("group_id",result.getString("group_id")).put("task_id","one"),parent(),DelegateBudget(),host))
        assertTrue(read.getString("text").contains("read"));assertTrue(read.getString("text").contains("parent_checks_required"))
    }
    @Test fun parentPrivateCapabilityResultsAreNotInheritedAsEvidence()=runBlocking<Unit> {
        rootFile("one.txt");val state=parent()
        state.put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(function("private","personal_memory",JSONObject().put("action","search").put("query","preference")))))
            .put(JSONObject().put("role","tool").put("tool_call_id","private").put("content","[{\"text\":\"Private remembered fact\"}]"))
        val host=Host();val result=JSONObject(DelegateEngine(File(temp.root,"delegates"),WorkspaceFileTools(workspace)).execute("chat",args(spec().put("evidence",JSONArray(listOf("private")))),state,DelegateBudget(),host))
        assertEquals("partial",result.getString("status"));assertEquals(0,host.calls.get());assertFalse(result.toString().contains("Private remembered fact"))
    }
    @Test fun privateCodingProposalRequiresGuardedMergeAndNeverCountsAsCompletionBeforeIt()=runBlocking<Unit> {
        val file=rootFile("one.txt");val files=WorkspaceFileTools(workspace);val engine=DelegateEngine(File(temp.root,"delegates"),files);val state=parent();val host=Host(write=true)
        val result=JSONObject(engine.execute("chat",args(spec(write=true)),state,DelegateBudget(),host))
        assertEquals("original",file.readText());assertTrue(result.getBoolean("has_unmerged_proposals"));assertTrue(CompletionEvidence.unsuccessful(result.toString()))
        val child=result.getJSONArray("children").getJSONObject(0);val merge=JSONObject().put("action","merge").put("group_id",result.getString("group_id")).put("task_id","one").put("expected_proposal",child.getString("proposal_digest"))
        assertEquals("ok",JSONObject(engine.execute("chat",merge,state,DelegateBudget(),host)).getString("status"));assertEquals("proposed-one",file.readText())
        assertTrue(JSONObject(engine.execute("chat",merge,state,DelegateBudget(),host)).getBoolean("reused"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine.execute("chat",JSONObject(merge.toString()).put("expected_proposal","a".repeat(64)),state,DelegateBudget(),host) } }
    }
    @Test fun changedSourcesRulesAndParentRequirementsPreventMergeWithoutOverwriting()=runBlocking<Unit> {
        val file=rootFile("one.txt");val engine=DelegateEngine(File(temp.root,"delegates"),WorkspaceFileTools(workspace));val state=parent();val host=Host(write=true)
        val result=JSONObject(engine.execute("chat",args(spec(write=true)),state,DelegateBudget(),host));val child=result.getJSONArray("children").getJSONObject(0)
        val merge=JSONObject().put("action","merge").put("group_id",result.getString("group_id")).put("task_id","one").put("expected_proposal",child.getString("proposal_digest"))
        file.writeText("later user edit")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine.execute("chat",merge,state,DelegateBudget(),host) } };assertEquals("later user edit",file.readText())
        file.writeText("original");rootFile("AGENTS.md","New rule")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine.execute("chat",merge,state,DelegateBudget(),host) } }
        workspace.file("chat","AGENTS.md").delete();TaskState.userChanges(state,listOf("Do not apply that proposal"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine.execute("chat",merge,state,DelegateBudget(),host) } };assertEquals("original",file.readText())
    }
    @Test fun duplicateKeysReuseReceiptsAndCrossScopeReadsCannotClaimThem()=runBlocking<Unit> {
        rootFile("one.txt");val engine=DelegateEngine(File(temp.root,"delegates"),WorkspaceFileTools(workspace));val host=Host();val state=parent();val request=args(spec())
        val first=JSONObject(engine.execute("chat",request,state,DelegateBudget(),host));val count=host.calls.get()
        assertTrue(JSONObject(engine.execute("chat",request,state,DelegateBudget(),host)).getBoolean("reused"));assertEquals(count,host.calls.get())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine.execute("chat",args(spec("different")),state,DelegateBudget(),host) } }
        assertThrows(Exception::class.java) { runBlocking { engine.execute("other",JSONObject().put("action","read").put("group_id",first.getString("group_id")),parent(),DelegateBudget(),host) } }
    }
    @Test fun rolesOverlapTraversalAndSymlinksAreRejectedBeforePaidInference()=runBlocking<Unit> {
        rootFile("one.txt");val engine=DelegateEngine(File(temp.root,"delegates"),WorkspaceFileTools(workspace));val host=Host()
        for(request in listOf(args(spec(write=true),spec("two",true).put("write_paths",JSONArray(listOf("one.txt")))),args(spec().put("allow_write",true)),args(spec().put("files",JSONArray(listOf("../secret")))))) {
            runCatching { engine.execute("chat",request,parent(),DelegateBudget(),host) };assertEquals(0,host.calls.get())
        }
        Files.createSymbolicLink(workspace.file("chat","link.txt").toPath(),workspace.file("chat","one.txt").toPath())
        val result=JSONObject(engine.execute("chat",args(spec().put("files",JSONArray(listOf("link.txt"))),key="links"),parent(),DelegateBudget(),host))
        assertEquals("partial",result.getString("status"));assertEquals(0,host.calls.get())
    }
    @Test fun forbiddenChildCallsRejectTheEntireBatchBeforeAnyWrite()=runBlocking<Unit> {
        val file=rootFile("one.txt");val engine=DelegateEngine(File(temp.root,"delegates"),WorkspaceFileTools(workspace))
        val host=object:Host(write=true) {
            override suspend fun infer(messages:JSONArray,tools:JSONArray,output:Int,beforeAttempt:()->Unit):JSONObject {
                beforeAttempt();return response(function("write","write_file",JSONObject().put("path","one.txt").put("content","bad")),function("phone","android",JSONObject().put("action","tap")))
            }
        }
        val result=JSONObject(engine.execute("chat",args(spec(write=true)),parent(),DelegateBudget(),host));assertEquals("partial",result.getString("status"));assertEquals("original",file.readText())
        assertEquals("failed",result.getJSONArray("children").getJSONObject(0).getString("state"))
    }
    @Test fun steeringAndParentCancellationStopChildrenAndLeaveParentFilesUntouched()=runBlocking<Unit> {
        val file=rootFile("one.txt");val engine=DelegateEngine(File(temp.root,"delegates"),WorkspaceFileTools(workspace));val host=Host(delayMs=10000)
        val run=async { engine.execute("chat",args(spec()),parent(),DelegateBudget(),host) }
        withTimeout(3000) { while(host.calls.get()==0)yield() };host.proceed=false;engine.interrupt("chat")
        val result=JSONObject(run.await());assertEquals("partial",result.getString("status"));assertEquals("original",file.readText());assertEquals(1,host.calls.get())
        val nextHost=Host(delayMs=10000);val cancelled=launch { engine.execute("chat",args(spec(),key="cancelled"),parent(),DelegateBudget(),nextHost) }
        withTimeout(3000) { while(nextHost.calls.get()==0)yield() };cancelled.cancelAndJoin();assertEquals(0,nextHost.concurrent.get())
    }
    @Test fun requestAndTokenLimitsIncludeRetriesAndKeepParentDeliveryCapacity()=runBlocking<Unit> {
        val requests=ModelRequests(maximum=6,reservedDelivery=1,pause={});val budget=DelegateBudget(maximum=2)
        var calls=0
        assertThrows(DelegateLimit::class.java) { runBlocking {
            requests.execute(ModelRequests.Purpose.DELEGATION,beforeAttempt={budget.reserve(100)}) { calls++;throw IOException("transient") }
        } }
        assertEquals(2,calls);assertEquals(2,requests.used)
        requests.execute(ModelRequests.Purpose.EXECUTION) { JSONObject() }
        requests.execute(ModelRequests.Purpose.EXECUTION) { JSONObject() }
        requests.execute(ModelRequests.Purpose.EXECUTION) { JSONObject() }
        requests.execute(ModelRequests.Purpose.DELIVERY) { JSONObject() };assertEquals(6,requests.used)
        val tokens=DelegateBudget(tokenLimit=10);tokens.reserve(10);assertThrows(DelegateLimit::class.java) { tokens.reserve(1) }
    }
    @Test fun concurrentRequestReservationCannotOverspendAndAttemptIdsAreUnique()=runBlocking<Unit> {
        val requests=ModelRequests(maximum=10,reservedDelivery=1);val seen=ConcurrentHashMap.newKeySet<Int>()
        coroutineScope { (0 until 20).map { async(Dispatchers.Default) { runCatching { requests.execute(ModelRequests.Purpose.DELEGATION,beforeAttempt={seen.add(it)}) { delay(2);JSONObject() } } } }.awaitAll() }
        assertEquals(7,requests.used);assertEquals(7,seen.size)
    }
    @Test fun restartNeverReplaysChildRequestsOrAnUnknownMerge()=runBlocking<Unit> {
        val file=rootFile("one.txt");val files=WorkspaceFileTools(workspace);val engine=DelegateEngine(File(temp.root,"delegates"),files);val host=Host(write=true);val state=parent()
        val result=JSONObject(engine.execute("chat",args(spec(write=true)),state,DelegateBudget(),host));val group=result.getString("group_id")
        val metadata=File(temp.root,"delegates/chat/revisions/main/$group/record.json");val saved=JSONObject(metadata.readText()).put("state","merging");metadata.writeText(saved.toString())
        val fresh=DelegateEngine(File(temp.root,"delegates"),files);val receipt=JSONObject(fresh.execute("chat",JSONObject().put("action","read").put("group_id",group),state,DelegateBudget(),Host()))
        assertEquals("merge_interrupted",receipt.getString("state"));assertEquals("original",file.readText())
        val attempts=host.calls.get();fresh.execute("chat",args(spec(write=true)),state,DelegateBudget(),host);assertEquals(attempts,host.calls.get())
    }
    @Test fun newFileGuardCannotReplaceAnExistingTargetAndArtifactReadsAreVersioned()=runBlocking<Unit> {
        val file=rootFile("one.txt");val files=WorkspaceFileTools(workspace)
        assertEquals("error",JSONObject(files.execute("chat","write_file",JSONObject().put("path","one.txt").put("content","bad").put("expected_absent",true))).getString("status"));assertEquals("original",file.readText())
        val engine=DelegateEngine(File(temp.root,"delegates"),files);val host=Host(write=true);val state=parent();val result=JSONObject(engine.execute("chat",args(spec(write=true)),state,DelegateBudget(),host))
        val read=JSONObject().put("action","read").put("group_id",result.getString("group_id")).put("task_id","one").put("view","artifact").put("path","one.txt")
        assertEquals("proposed-one",JSONObject(engine.execute("chat",read,state,DelegateBudget(),host)).getString("text"))
        assertThrows(IllegalStateException::class.java) { runBlocking { engine.execute("chat",JSONObject(read.toString()).put("path","unowned.txt"),state,DelegateBudget(),host) } }
    }
    @Test fun deadlinesAndSourceChangesReturnPartialWithoutStaleSuccess()=runBlocking<Unit> {
        val file=rootFile("one.txt");val files=WorkspaceFileTools(workspace);val engine=DelegateEngine(File(temp.root,"delegates"),files,deadlineMs=40)
        val slow=Host(delayMs=10000);val result=JSONObject(withTimeout(2000) { engine.execute("chat",args(spec()),parent(),DelegateBudget(),slow) })
        assertEquals("partial",result.getString("status"));assertEquals("interrupted",result.getString("state"));assertEquals(0,slow.concurrent.get())
        val changing=object:Host() {
            override suspend fun infer(messages:JSONArray,tools:JSONArray,output:Int,beforeAttempt:()->Unit):JSONObject {
                val result=super.infer(messages,tools,output,beforeAttempt)
                if(messages.length()>2)file.writeText("new parent version")
                return result
            }
        }
        val stale=JSONObject(DelegateEngine(File(temp.root,"delegates"),files).execute("chat",args(spec(),key="stale"),parent(),DelegateBudget(),changing))
        assertEquals("partial",stale.getString("status"));assertEquals("stale_inputs",stale.getJSONArray("children").getJSONObject(0).getString("state"))
    }
    @Test fun partialAssessmentsMayCiteFailuresButCompletedAssessmentsCannot()=runBlocking<Unit> {
        rootFile("one.txt");val engine=DelegateEngine(File(temp.root,"delegates"),WorkspaceFileTools(workspace))
        for(outcome in listOf("blocked","completed")) {
            val step=AtomicInteger();val host=object:Host() {
                override suspend fun infer(messages:JSONArray,tools:JSONArray,output:Int,beforeAttempt:()->Unit):JSONObject {
                    beforeAttempt()
                    return if(step.incrementAndGet()==1)response(function("missing","read_file",JSONObject().put("path","missing.txt")))
                    else response(function("finish","delegate_finish",JSONObject().put("outcome",outcome).put("summary","The requested input is absent").put("evidence",JSONArray(listOf("missing"))).put("unresolved",JSONArray(if(outcome=="blocked")listOf("Missing input") else emptyList<String>()))))
                }
            }
            val result=JSONObject(engine.execute("chat",args(spec(),key=outcome),parent(),DelegateBudget(),host))
            assertEquals(if(outcome=="blocked")"blocked" else "failed",result.getJSONArray("children").getJSONObject(0).getString("state"))
        }
    }
    @Test fun appliedSyntaxErrorsRemainPartialAcrossMergeReplayAndGroupReuse()=runBlocking<Unit> {
        val file=rootFile("one.json","{}")
        val coding=ai.capri.app.coding.CodingStore(File(temp.root,"coding"))
        val engine=DelegateEngine(File(temp.root,"delegates"),WorkspaceFileTools(workspace,coding));val state=parent();val step=AtomicInteger()
        val host=object:Host() {
            override suspend fun infer(messages:JSONArray,tools:JSONArray,output:Int,beforeAttempt:()->Unit):JSONObject {
                beforeAttempt()
                return when(step.incrementAndGet()) {
                    1 -> response(function("read","read_file",JSONObject().put("path","one.json")))
                    2 -> response(function("write","write_file",JSONObject().put("path","one.json").put("content","{broken")))
                    else -> response(finished("write"))
                }
            }
        }
        val request=args(spec(write=true).put("files",JSONArray(listOf("one.json"))).put("write_paths",JSONArray(listOf("one.json"))))
        val proposal=JSONObject(engine.execute("chat",request,state,DelegateBudget(),host))
        val merge=JSONObject().put("action","merge").put("group_id",proposal.getString("group_id")).put("task_id","one").put("expected_proposal",proposal.getJSONArray("children").getJSONObject(0).getString("proposal_digest"))
        val result=JSONObject(engine.execute("chat",merge,state,DelegateBudget(),host))
        assertEquals("{broken",file.readText());assertEquals("partial",result.getString("status"));assertTrue(CompletionEvidence.unsuccessful(result.toString()))
        val snapshots=coding.list("chat").length()
        val replay=JSONObject(engine.execute("chat",merge,state,DelegateBudget(),host))
        assertTrue(replay.getBoolean("reused"));assertEquals("partial",replay.getString("status"));assertEquals(snapshots,coding.list("chat").length())
        val reused=JSONObject(engine.execute("chat",request,state,DelegateBudget(),host))
        assertEquals("partial",reused.getString("status"));assertTrue(reused.getBoolean("result_incomplete"));assertEquals(3,step.get())
    }
    @Test fun interruptedMultiFileMergePreservesLaterTargetsAndNeverReplays()=runBlocking<Unit> {
        val first=rootFile("one.txt");val second=rootFile("two.txt");lateinit var engine:DelegateEngine
        val coding=ai.capri.app.coding.CodingStore(File(temp.root,"coding"))
        val files=WorkspaceFileTools(workspace,coding) { _,_ -> engine.interrupt("chat") }
        engine=DelegateEngine(File(temp.root,"delegates"),files)
        val step=AtomicInteger();val host=object:Host(write=true) {
            override suspend fun infer(messages:JSONArray,tools:JSONArray,output:Int,beforeAttempt:()->Unit):JSONObject {
                beforeAttempt()
                return when(step.incrementAndGet()) {
                    1 -> response(function("r1","read_file",JSONObject().put("path","one.txt")),function("r2","read_file",JSONObject().put("path","two.txt")))
                    2 -> response(function("w1","write_file",JSONObject().put("path","one.txt").put("content","first proposal")),function("w2","write_file",JSONObject().put("path","two.txt").put("content","second proposal")))
                    else -> response(finished("w1"))
                }
            }
        }
        val state=parent();val result=JSONObject(engine.execute("chat",args(spec(write=true).put("files",JSONArray(listOf("one.txt","two.txt"))).put("write_paths",JSONArray(listOf("one.txt","two.txt")))),state,DelegateBudget(),host))
        val merge=JSONObject().put("action","merge").put("group_id",result.getString("group_id")).put("task_id","one").put("expected_proposal",result.getJSONArray("children").getJSONObject(0).getString("proposal_digest"))
        assertThrows(IllegalStateException::class.java) { runBlocking { engine.execute("chat",merge,state,DelegateBudget(),host) } }
        assertEquals("first proposal",first.readText());assertEquals("original",second.readText())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine.execute("chat",merge,state,DelegateBudget(),host) } }
    }
    @Test fun retiringOutputKeepsAnIdempotencyPointerAndNeverMistakesInputJsonForReceipts()=runBlocking<Unit> {
        rootFile("one.txt");val engine=DelegateEngine(File(temp.root,"delegates"),WorkspaceFileTools(workspace));val host=Host();val state=parent();val request=args(spec())
        val old=JSONObject(engine.execute("chat",request,state,DelegateBudget(),host));val scope=File(temp.root,"delegates/chat/revisions/main")
        File(scope,"${old.getString("group_id")}/record.json").setLastModified(1)
        repeat(31) { index -> val id="%032x".format(index);val dir=File(scope,id).apply { mkdirs() };File(dir,"record.json").writeText(JSONObject().put("group_id",id).put("scope","chat").put("request_hash","fixture").put("state","discarded").put("children",JSONArray()).toString()) }
        engine.execute("chat",args(spec(),key="next"),state,DelegateBudget(),host)
        val paid=host.calls.get();val reused=JSONObject(engine.execute("chat",request,state,DelegateBudget(),host))
        assertEquals("retired",reused.getString("state"));assertTrue(reused.getBoolean("reused"));assertEquals(paid,host.calls.get())
        // Synthetic active proposals fill the receipt quota. A project file named record.json is data.
        scope.deleteRecursively();scope.mkdirs()
        repeat(32) { index -> val id="%032x".format(index);val dir=File(scope,id).apply { mkdirs() };File(dir,"record.json").writeText(JSONObject().put("group_id",id).put("scope","chat").put("request_hash","fixture").put("state","complete").put("children",JSONArray().put(JSONObject().put("state","proposal_ready"))).toString()) }
        val data=File(scope,"${"%032x".format(0)}/one/sessions/child/workspace/record.json").apply { parentFile!!.mkdirs();writeText("{\"state\":\"failed\"}") }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine.execute("chat",args(spec(),key="quota"),state,DelegateBudget(),host) } }
        assertTrue(data.isFile);assertEquals(paid,host.calls.get())
    }
    @Test fun serialAndParallelFixturesMeasureCoordinationCostWithoutClaimingModelQuality()=runBlocking<Unit> {
        rootFile("one.txt");rootFile("two.txt");val engine=DelegateEngine(File(temp.root,"delegates"),WorkspaceFileTools(workspace))
        val directFiles=WorkspaceFileTools(workspace);val directMessages=parent();var directUsed=0;var directReads=0
        val directStart=System.nanoTime()
        val direct=AgentLoop(object:AgentLoop.Host {
            override val used get()=directUsed
            override val maximum=8
            override val request="Review both declared inputs"
            override suspend fun prepare()=AgentLoop.Input(directMessages,JSONArray(FileToolContract.specifications().filter { it.getJSONObject("function").getString("name")=="read_file" }),1000)
            override suspend fun infer(input:AgentLoop.Input):JSONObject {
                directUsed++;delay(30)
                return if(directUsed==1)response(function("d1","read_file",JSONObject().put("path","one.txt")),function("d2","read_file",JSONObject().put("path","two.txt")))
                else JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("role","assistant").put("content","Both declared files were read and checked."))))
            }
            override suspend fun rebudget(error:ai.capri.app.model.ModelContextRejected,input:AgentLoop.Input) { throw IllegalStateException("Unexpected rejection") }
            override suspend fun observe(input:AgentLoop.Input,response:JSONObject) {}
            override fun append(message:JSONObject) { directMessages.put(message) }
            override suspend fun checkpoint() {}
            override fun decision(choice:JSONObject,count:Int,decision:String,corrections:Int) {}
            override fun preamble(content:String,tools:List<String>) {}
            override fun status(message:String) {}
            override suspend fun execute(call:JSONObject):String { directReads++;return directFiles.execute("chat",call.getJSONObject("function").getString("name"),JSONObject(call.getJSONObject("function").getString("arguments"))) }
            override suspend fun record(call:JSONObject,result:String) { assertEquals("original",JSONObject(result).getString("content").trim());directMessages.put(JSONObject().put("role","tool").put("tool_call_id",call.getString("id")).put("content",result)) }
            override suspend fun deliver(draft:String) { assertEquals(2,directReads) }
        }).run()
        val directMs=(System.nanoTime()-directStart)/1_000_000;assertEquals(AgentLoop.Outcome.COMPLETE,direct)
        val serial=Host(delayMs=30);val before=System.nanoTime();val a=JSONObject(engine.execute("chat",args(spec(),spec("two"),key="serial",parallel=false),parent(),DelegateBudget(),serial));val serialMs=(System.nanoTime()-before)/1_000_000
        val parallel=Host(delayMs=30);val started=System.nanoTime();val b=JSONObject(engine.execute("chat",args(spec(),spec("two"),key="parallel"),parent(),DelegateBudget(),parallel));val parallelMs=(System.nanoTime()-started)/1_000_000
        assertEquals("ok",a.getString("status"));assertEquals("ok",b.getString("status"));assertEquals(serial.calls.get(),parallel.calls.get());assertEquals(1,serial.peak.get());assertEquals(2,parallel.peak.get())
        println("DELEGATION_METRICS "+JSONObject().put("fixture","scripted_source_review").put("single_agent_ms",directMs).put("single_agent_requests",directUsed).put("serial_ms",serialMs).put("parallel_ms",parallelMs).put("requests_each",serial.calls.get()).put("parallel_peak",parallel.peak.get()).put("success_each",2).put("real_model_quality","not_measured"))
    }
}
