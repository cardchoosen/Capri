package ai.capri.app.program

import ai.capri.app.agent.CompletionEvidence
import ai.capri.app.agent.TaskState
import ai.capri.app.agent.ToolRegistry
import ai.capri.app.agent.ToolScheduler
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ProgramEngineTest {
    private fun execute(code:String,ms:Int=5000)=JSONObject().put("action","execute").put("code",code).put("timeout_ms",ms)
    private fun request(name:String)=JSONObject().put("name",name).put("arguments",JSONObject())
    private fun engine(root:File,idle:Long=60000)=ProgramEngine(root,{ f,s -> f.parentFile!!.mkdirs();f.writeText(s) },{ it.readText() },idle)
    private class Host(val root:File):ProgramEngine.Host {
        var fingerprint="fixture-v1";var allowed=true;var starts=0
        val dispatched=mutableListOf<String>()
        var callback:suspend(JSONObject)->String={ "{\"status\":\"ok\",\"values\":[1,2,3]}" }
        override suspend fun launch(chat:String):Process {
            starts++
            val source=listOf(File("src/main/assets/runtime/agent_kernel.py"),File("app/src/main/assets/runtime/agent_kernel.py")).first { it.isFile }.absoluteFile
            // Host protocol fixture only: native RLIMIT and waitid behavior is exercised by the device case.
            return ProcessBuilder("python3","-u","-c","import resource,runpy,sys,os;resource.setrlimit=lambda *args:None;os.waitid=lambda *args:(_ for _ in ()).throw(ChildProcessError());runpy.run_path(sys.argv[1])",source.path)
                .directory(File(root,chat).apply { mkdirs() }).start()
        }
        override fun stop(process:Process) { process.destroyForcibly();process.waitFor() }
        override fun fingerprint(chat:String)=fingerprint
        override suspend fun canExecute()=allowed
        override suspend fun call(call:JSONObject):String { synchronized(dispatched){dispatched+=call.getString("id")};return callback(call) }
    }
    private fun fixture(block:suspend(ProgramEngine,Host,File)->Unit)=runBlocking {
        val root=Files.createTempDirectory("program-engine").toFile();val engine=engine(File(root,"receipts"));val host=Host(root)
        try { block(engine,host,root) } finally { engine.dispose();root.deleteRecursively() }
    }
    private fun rejected(block:()->Unit) { try { block();fail() } catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {} }
    @Test fun variablesAndImportsPersistUntilExplicitReset()=fixture { e,h,_ ->
        val one=JSONObject(e.execute("chat",execute("import math\nvalue=math.sqrt(81)\ncapri.emit(value)"),h))
        val two=JSONObject(e.execute("chat",execute("capri.emit(value+1)"),h))
        assertEquals("ok",one.getString("status"));assertEquals(10.0,two.getJSONArray("emitted").getDouble(0),0.0)
        assertEquals(one.getString("kernel_id"),two.getString("kernel_id"));assertTrue(two.getBoolean("reused"));assertEquals(1,h.starts)
        e.execute("chat",JSONObject().put("action","reset"),h)
        val missing=JSONObject(e.execute("chat",execute("capri.emit(value)"),h));assertEquals("error",missing.getString("status"));assertEquals(2,h.starts)
    }
    @Test fun rpcResultsStayInExecutionWhileSelectedSummaryIsReturned()=fixture { e,h,_ ->
        h.callback={ "{\"status\":\"ok\",\"secret_middle\":\"INTERMEDIATE ONLY\",\"values\":[1,2,3]}" }
        val result=JSONObject(e.execute("chat",execute("data=capri.call('read_file',{})\ncapri.emit({'total':sum(data['values'])})"),h,"parent-call"))
        assertEquals("ok",result.getString("status"));assertEquals(6,result.getJSONArray("emitted").getJSONObject(0).getInt("total"))
        assertFalse(result.toString().contains("INTERMEDIATE ONLY"))
        val receipt=JSONObject(e.execute("chat",JSONObject().put("action","read").put("run_id",result.getString("run_id")),h))
        assertTrue(receipt.getString("text").contains("INTERMEDIATE ONLY"));assertTrue(receipt.getString("text").contains("parent-call"))
    }
    @Test fun fullBatchIsValidatedBeforeAnyDispatch()=fixture { e,h,_ ->
        val args=JSONObject().put("action","batch").put("calls",JSONArray().put(request("read_file")).put(request("write_file")))
        rejected { ProgramContract.validate(args) };assertTrue(h.dispatched.isEmpty())
        for(target in listOf("program_run","tool_invoke","task_state","extensions","terminal","mcp_anything","personal_memory"))
            rejected { ProgramContract.calls(JSONArray().put(request(target)),16,false) }
    }
    @Test fun failedChildMakesBatchAndCaughtScriptFailuresExplicitlyPartial()=fixture { e,h,_ ->
        h.callback={ "{\"status\":\"permission_denied\",\"message\":\"Denied by target\"}" }
        val script="try:\n capri.call('read_file',{})\nexcept Exception:\n pass\ncapri.emit('handled')"
        val result=JSONObject(e.execute("chat",execute(script),h));assertEquals("partial",result.getString("status"));assertEquals(1,result.getInt("failed_calls"))
        assertTrue(CompletionEvidence.unsuccessful(result.toString()))
        val batch=JSONObject(e.execute("chat",JSONObject().put("action","batch").put("calls",JSONArray().put(request("read_file")).put(request("list_files"))),h))
        assertEquals("partial",batch.getString("status"));assertEquals(2,batch.getJSONArray("results").length())
    }
    @Test fun timeoutDiscardsStateAndKeepsQueryableReceipt()=fixture { e,h,_ ->
        e.execute("chat",execute("value=123"),h)
        val result=JSONObject(e.execute("chat",execute("while True: pass",300),h))
        assertEquals("timeout",result.getString("status"))
        val status=JSONObject(e.execute("chat",JSONObject().put("action","status"),h));assertEquals("absent",status.getString("kernel_state"));assertTrue(status.getJSONArray("recent_runs").length()>=2)
        val receipt=JSONObject(e.execute("chat",JSONObject().put("action","read").put("run_id",result.getString("run_id")),h))
        assertTrue(receipt.getBoolean("result_incomplete"))
    }
    @Test fun cancellationRetainsUnknownChildIntentWithoutReplayingIt()=fixture { e,h,_ ->
        val started=CompletableDeferred<Unit>();h.callback={ started.complete(Unit);awaitCancellation() }
        val job=CoroutineScope(currentCoroutineContext()).launch { e.execute("chat",execute("capri.call('write_file',{})"),h,"parent") }
        started.await();job.cancelAndJoin()
        val status=JSONObject(e.execute("chat",JSONObject().put("action","status"),h))
        val ref=status.getJSONArray("recent_runs").getJSONObject(0).getString("run_id")
        val read=JSONObject(e.execute("chat",JSONObject().put("action","read").put("run_id",ref),h))
        assertTrue(read.getString("text").contains("pending"));assertEquals(1,h.dispatched.size)
        e.execute("chat",JSONObject().put("action","status"),h);assertEquals(1,h.dispatched.size)
    }
    @Test fun outputAndCallBudgetsCannotBeHiddenByCatchingExceptions()=fixture { e,h,_ ->
        val output=JSONObject(e.execute("chat",execute("try:\n print('x'*3000)\nexcept Exception:\n pass").put("max_output_chars",1024),h))
        assertEquals("error",output.getString("status"))
        val calls=JSONObject(e.execute("chat",execute("for _ in range(3):\n try:\n  capri.call('read_file',{})\n except Exception:\n  pass").put("max_calls",1),h))
        assertEquals("error",calls.getString("status"));assertEquals(1,h.dispatched.size)
    }
    @Test fun revisionAndEnvironmentChangesInvalidateVariables()=fixture { e,h,_ ->
        val old=JSONObject(e.execute("chat",execute("value=123"),h))
        val branch="chat:history:12345678-1234-1234-1234-123456789abc"
        assertEquals("error",JSONObject(e.execute(branch,execute("capri.emit(value)"),h)).getString("status"))
        assertEquals("absent",JSONObject(e.execute("chat",JSONObject().put("action","status"),h)).getString("kernel_state"))
        val next=JSONObject(e.execute("chat",execute("value=456"),h));h.fingerprint="fixture-v2"
        val result=JSONObject(e.execute("chat",execute("capri.emit(globals().get('value','absent'))"),h))
        assertEquals("absent",result.getJSONArray("emitted").getString(0));assertFalse(result.getBoolean("reused"))
        assertNotEquals(next.getString("kernel_id"),result.getString("kernel_id"));assertNotEquals(old.getString("kernel_id"),result.getString("kernel_id"))
    }
    @Test fun receiptsCannotCrossChatOrHistoryRevision()=fixture { e,h,_ ->
        val result=JSONObject(e.execute("chat",execute("capri.emit('fixture')"),h));val args=JSONObject().put("action","read").put("run_id",result.getString("run_id"))
        try { e.execute("other",args,h);fail() } catch(_:java.io.FileNotFoundException) {}
        try { e.execute("chat:history:12345678-1234-1234-1234-123456789abc",args,h);fail() } catch(_:java.io.FileNotFoundException) {}
    }
    @Test fun idleExpiryStopsKernelWithoutPolling()=runBlocking {
        val root=Files.createTempDirectory("program-idle").toFile();val e=engine(File(root,"receipts"),80);val h=Host(root)
        try { e.execute("chat",execute("value=1"),h);delay(180);assertEquals("absent",JSONObject(e.execute("chat",JSONObject().put("action","status"),h)).getString("kernel_state")) }
        finally { e.dispose();root.deleteRecursively() }
    }
    @Test fun networkConcurrencyIsSharedAcrossBatchesAndResultsKeepOrder()=fixture { e,h,_ ->
        val active=AtomicInteger();val maximum=AtomicInteger()
        h.callback={ call -> val n=active.incrementAndGet();maximum.updateAndGet { maxOf(it,n) };try { delay(30);"{\"status\":\"ok\"}" }finally { active.decrementAndGet() } }
        val calls=JSONArray();repeat(6) { calls.put(request("web_read")) }
        val result=JSONObject(e.execute("chat",JSONObject().put("action","batch").put("calls",calls),h))
        assertTrue(maximum.get()<=2);assertEquals(6,result.getJSONArray("results").length())
        assertEquals((0..5).map { "${result.getString("run_id")}-$it" },(0..5).map { result.getJSONArray("results").getJSONObject(it).getString("id") })
    }
    @Test fun schemaAndRuntimeRejectWrongActionFields() {
        val schema=ProgramContract.specification().getJSONObject("function").getJSONObject("parameters")
        rejected { ai.capri.app.agent.ToolInput.check(JSONObject().put("action","status").put("code","print(1)"),schema) }
        rejected { ProgramContract.validate(JSONObject().put("action","execute").put("code","x").put("max_calls",33)) }
        rejected { ProgramContract.validate(JSONObject().put("action","batch").put("calls",JSONArray())) }
        assertFalse(ProgramContract.specification().toString().any { it in '\u4e00'..'\u9fff' })
    }
    @Test fun kernelCapacityIsBoundedAndResetFreesTheSlot()=fixture { e,h,_ ->
        assertEquals("ok",JSONObject(e.execute("one",execute("value=1"),h)).getString("status"))
        assertEquals("ok",JSONObject(e.execute("two",execute("value=2"),h)).getString("status"))
        assertEquals("error",JSONObject(e.execute("three",execute("value=3"),h)).getString("status"));assertEquals(2,h.starts)
        e.execute("one",JSONObject().put("action","reset"),h)
        assertEquals("ok",JSONObject(e.execute("three",execute("value=3"),h)).getString("status"))
        assertEquals(2,JSONObject(e.execute("two",execute("capri.emit(value)"),h)).getJSONArray("emitted").getInt(0))
    }
    @Test fun truncatedRpcResultsAreExplicitAndOriginalEvidenceRemainsPaged()=fixture { e,h,_ ->
        h.callback={ JSONObject().put("status","ok").put("output","x".repeat(30000)).toString() }
        val result=JSONObject(e.execute("chat",execute("data=capri.call('read_file',{})\ncapri.emit(data['truncated'])"),h))
        assertEquals("partial",result.getString("status"));assertEquals(1,result.getInt("truncated_calls"))
        val page=JSONObject(e.execute("chat",JSONObject().put("action","read").put("run_id",result.getString("run_id")),h))
        assertTrue(page.getBoolean("has_more"));assertEquals(8000,page.getInt("next_offset"))
        h.callback={ "{\"status\":\"ok\",\"truncated\":true,\"reference\":\"r-fixture\"}" }
        val archived=JSONObject(e.execute("chat",execute("data=capri.call('read_file',{})\ncapri.emit(data['truncated'])"),h))
        assertEquals("partial",archived.getString("status"));assertEquals(1,archived.getInt("truncated_calls"))
    }
    @Test fun queuedSteeringPreventsDispatchAndStatusIsNotWorkEvidence()=fixture { e,h,_ ->
        h.allowed=false
        val result=JSONObject(e.execute("chat",execute("capri.emit('must not run')"),h));assertEquals("error",result.getString("status"));assertEquals(0,h.starts)
        val messages=JSONArray().put(JSONObject().put("role","system"));TaskState.ensure(messages,"Hello",false)
        messages.put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(JSONObject().put("id","status").put("function",JSONObject().put("name","program_run").put("arguments","{\"action\":\"status\"}")))))
        messages.put(JSONObject().put("role","tool").put("tool_call_id","status").put("content","{\"status\":\"ok\"}"))
        assertTrue(CompletionEvidence.results(messages).isEmpty())
    }
}
