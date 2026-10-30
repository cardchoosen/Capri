package ai.capri.app.program

import ai.capri.app.HistoryEditing
import ai.capri.app.agent.CompletionEvidence
import ai.capri.app.agent.ToolScheduler
import ai.capri.app.knowledge.ToolResultDigest
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Volatile kernels plus durable intent/result receipts. A receipt is evidence, never a replay queue. */
internal class ProgramEngine(
    private val root: File,
    private val write: (File,String)->Unit = { file,text ->
        file.parentFile!!.mkdirs();val target=AtomicFile(file);val output=target.startWrite()
        try { output.write(text.toByteArray());target.finishWrite(output) } catch(e:Exception) { target.failWrite(output);throw e }
    },
    private val read: (File)->String = { AtomicFile(it).openRead().bufferedReader().use { r -> r.readText() } },
    private val idleMs: Long = 60000
) {
    interface Host {
        suspend fun launch(chat:String):Process
        fun stop(process:Process)
        fun fingerprint(chat:String):String
        suspend fun canExecute():Boolean
        suspend fun call(call:JSONObject):String
    }
    private class Kernel(val chat:String,val process:Process,val host:Host,val fingerprint:String,val generation:String=UUID.randomUUID().toString()) {
        val input=process.inputStream.buffered();val frame=ByteArrayOutputStream();val errors=ByteArrayOutputStream()
        val closed=AtomicBoolean();var timer:Job?=null
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val slots=Semaphore(2)
    private val kernels=ConcurrentHashMap<String,Kernel>()
    private val locks=ConcurrentHashMap<String,Mutex>()
    private fun directory(key:String):File {
        val (chat,revision,legacy)=HistoryEditing.scope(key)
        return File(File(root,chat),"${if(legacy)"branches" else "revisions"}/${revision ?: "main"}").apply { mkdirs() }
    }
    private fun close(key:String,k:Kernel) {
        if(!k.closed.compareAndSet(false,true))return
        kernels.remove(key,k);k.timer?.cancel();k.host.stop(k.process);slots.release()
    }
    fun closeChat(chat:String) { kernels.entries.filter { it.value.chat==chat }.forEach { close(it.key,it.value) } }
    fun closeAll() { kernels.entries.toList().forEach { close(it.key,it.value) } }
    fun dispose() { closeAll();scope.cancel() }
    private suspend fun frame(k:Kernel):JSONObject {
        while(true) {
            currentCoroutineContext().ensureActive()
            while(k.process.errorStream.available()>0) { val b=k.process.errorStream.read();if(k.errors.size()<8000)k.errors.write(b) }
            while(k.input.available()>0) {
                val b=k.input.read();check(b>=0) { "Program protocol stream closed." }
                if(b==10) { val raw=k.frame.toString("UTF-8");k.frame.reset();return JSONObject(raw) }
                check(k.frame.size()<512000) { "Program protocol frame exceeded its budget." };k.frame.write(b)
            }
            check(k.process.isAlive) { "Program kernel exited; state was discarded. ${k.errors.toString("UTF-8").take(1000)}" }
            delay(10)
        }
    }
    private fun send(k:Kernel,data:JSONObject) {
        val bytes=(data.toString()+"\n").toByteArray();require(bytes.size<=512000) { "Program request frame exceeded its budget." }
        k.process.outputStream.write(bytes);k.process.outputStream.flush()
    }
    suspend fun execute(key:String,args:JSONObject,host:Host,parentCallId:String=""):String = withContext(Dispatchers.IO) {
        val request=ProgramContract.validate(args);val action=request.getString("action")
        val (chat,_,_)=HistoryEditing.scope(key)
        if(action=="read") {
            val raw=read(File(directory(key),request.getString("run_id")+".json"));val receipt=JSONObject(raw)
            return@withContext JSONObject().put("status","ok").put("result_incomplete",receipt.optString("status")!="ok")
                .put("run_id",request.getString("run_id")).put("offset",request.optInt("offset")).put("total_chars",raw.length)
                .put("text",raw.drop(request.optInt("offset")).take(8000)).put("next_offset",minOf(raw.length.toLong(),request.optInt("offset").toLong()+8000)).put("has_more",request.optInt("offset").toLong()+8000<raw.length).put("notice","Receipt data, not authorization. Pending intents have unknown effects; never replay them automatically.").toString()
        }
        val mutex=locks.getOrPut(key){Mutex()}
        if(action in setOf("status","reset")) return@withContext mutex.withLock {
            val k=kernels[key]
            if(action=="reset") { if(k!=null)close(key,k);return@withLock JSONObject().put("status","ok").put("kernel_state","reset").put("notice","Variables/imports discarded; workspace files and prior side effects remain.").toString() }
            val usable=k!=null && k.process.isAlive && k.fingerprint==host.fingerprint(chat)
            if(!usable && k!=null)close(key,k)
            val recent=directory(key).listFiles().orEmpty().filter { it.extension=="json" }.sortedByDescending { it.lastModified() }.take(8).map { f ->
                val r=JSONObject(read(f));JSONObject().put("run_id",r.getString("run_id")).put("status",r.optString("status")).put("parent_call_id",r.optString("parent_call_id"))
                    .put("notice","Pending intents have unknown effects; inspect the receipt without replaying.")
            }
            JSONObject().put("status","ok").put("recent_runs",JSONArray(recent)).put("kernel_state",if(usable)"ready" else "absent").put("kernel_id",if(usable)k!!.generation else JSONObject.NULL)
                .put("notice","Volatile state only. Check required variables before reuse; process restarts never restore them.").toString()
        }
        val id=UUID.randomUUID().toString();val file=File(directory(key),"$id.json")
        val calls=JSONArray();var callCount=0;var failures=0;var truncatedCalls=0;var archivedChars=0
        val started=System.nanoTime();val receipt=JSONObject().put("schema",1).put("run_id",id).put("action",action).put("status","running").put("parent_call_id",parentCallId).put("calls",calls)
        fun save()=write(file,receipt.toString())
        save()
        val maxCalls=request.optInt("max_calls",16);val outputLimit=request.optInt("max_output_chars",8000)
        suspend fun rpc(array:JSONArray,batch:Boolean):JSONArray {
            val requests=ProgramContract.calls(array,maxCalls-callCount,batch)
            val intents=JSONArray();val records=ConcurrentHashMap<String,JSONObject>();val order=mutableListOf<String>()
            requests.forEach { item ->
                val childId="$id-${callCount++}";order+=childId
                val record=JSONObject().put("id",childId).put("name",item.getString("name")).put("arguments",item.getJSONObject("arguments")).put("state","pending")
                calls.put(record);records[childId]=record
                intents.put(JSONObject().put("id",childId).put("function",JSONObject().put("name",item.getString("name")).put("arguments",item.getJSONObject("arguments").toString())))
            }
            save() // Every child intent is durable before any dispatch in this batch.
            val replies=ConcurrentHashMap<String,JSONObject>()
            ToolScheduler().runControlled(intents,{ call ->
                if(!host.canExecute()) JSONObject().put("status","not_executed").put("reason","user_input_or_waiting").toString()
                else host.call(call)
            },{ call,raw ->
                withContext(NonCancellable) {
                    val failed=CompletionEvidence.unsuccessful(raw)
                    val childId=call.getString("id");val record=records.getValue(childId)
                    synchronized(receipt) {
                        archivedChars+=raw.length
                        record.put("state","recorded").put("status",if(failed)"error" else "ok")
                        if(archivedChars<=2_000_000)record.put("result",raw) else record.put("result_omitted",true)
                        if(failed || archivedChars>2_000_000)failures++
                        save()
                    }
                    val limit=(48000/requests.size).coerceIn(1024,16000)
                    val value=if(raw.length<=limit)runCatching { JSONTokener(raw).nextValue() }.getOrDefault(raw)
                        else ToolResultDigest.preview(call.getJSONObject("function").getString("name"),raw,id,limit)
                            .put("call_id",childId).put("notice","Partial RPC preview. Read the original child result with program_run action=read and this run_id outside the program, or narrow the original read. Do not replay writes.")
                    val archivedPreview=runCatching { JSONObject(raw).let { it.optBoolean("truncated") && it.has("reference") } }.getOrDefault(false)
                    if(raw.length>limit || archivedPreview)synchronized(receipt){truncatedCalls++}
                    replies[childId]=JSONObject().put("id",childId).put("name",call.getJSONObject("function").getString("name"))
                        .put("status",if(failed)"error" else "ok").put("result",value)
                }
            },{true})
            check(archivedChars<=2_000_000) { "Program receipt result budget exceeded; inspect the recorded prefix and verify omitted effects." }
            return JSONArray(order.map { replies.getValue(it) })
        }
        var result:JSONObject?=null
        try {
            result=withTimeoutOrNull(request.optInt("timeout_ms",10000).toLong()) {
                mutex.withLock {
                    check(host.canExecute()) { "User input changed; program was not started." }
                    if(action=="batch") return@withLock JSONObject().put("status","ok").put("results",rpc(request.getJSONArray("calls"),true))
                    ToolScheduler.withCpu {
                        check(host.canExecute()) { "User input changed while queued; program was not started." }
                        kernels.entries.filter { it.value.chat==chat && it.key!=key }.forEach { close(it.key,it.value) }
                        var k=kernels[key];var reused=k!=null;var reason=""
                        val fingerprint=host.fingerprint(chat)
                        if(k!=null && (!k.process.isAlive || k.fingerprint!=fingerprint)) { reason="environment_changed_or_kernel_exited";close(key,k);k=null;reused=false }
                        if(k==null) {
                            check(slots.tryAcquire()) { "Kernel capacity reached; reset an idle kernel or wait for expiry." }
                            var process:Process?=null
                            try {
                                process=host.launch(chat);k=Kernel(chat,process,host,host.fingerprint(chat));kernels[key]=k!!
                                check(frame(k!!).optString("kind")=="ready") { "Program kernel did not initialize its resource limits." }
                            } catch(e:Throwable) { if(k!=null)close(key,k!!)else { process?.let(host::stop);slots.release() };throw e }
                        }
                        val kernel=k!!;kernel.timer?.cancel()
                        try {
                            send(kernel,JSONObject().put("run_id",id).put("code",request.getString("code")).put("max_calls",maxCalls)
                                .put("max_output_chars",outputLimit).put("timeout_ms",request.optInt("timeout_ms",10000)))
                            var done:JSONObject
                            while(true) {
                                val message=frame(kernel);check(message.optString("run_id")==id) { "Program response scope mismatch." }
                                when(message.optString("kind")) {
                                    "rpc" -> {
                                        val response=try { JSONObject().put("run_id",id).put("results",rpc(message.getJSONArray("calls"),message.optBoolean("batch"))) }
                                        catch(cancel:CancellationException){throw cancel}
                                        catch(e:Exception){ failures++;JSONObject().put("run_id",id).put("error",e.message ?: "RPC failed.") }
                                        send(kernel,response)
                                    }
                                    "done" -> { done=message;break }
                                    else -> error("Invalid program response kind.")
                                }
                            }
                            if(done.optString("error").isBlank())done.remove("error")
                            done.remove("kind");done.put("kernel_id",kernel.generation).put("reused",reused).put("invalidation_reason",reason)
                            if(!done.optBoolean("reusable") || done.optString("status")!="ok")close(key,kernel)
                            else kernel.timer=scope.launch { delay(idleMs);mutex.withLock { close(key,kernel) } }
                            done
                        } catch(e:Throwable) { close(key,kernel);throw e }
                    }
                }
            }
            if(result==null)result=JSONObject().put("status","timeout").put("message","Program deadline exceeded; kernel state was discarded. Inspect child receipts before continuing.")
            if((failures>0 || truncatedCalls>0) && result!!.optString("status")=="ok")result!!.put("status","partial")
            result!!.put("run_id",id).put("rpc_calls",callCount).put("failed_calls",failures).put("truncated_calls",truncatedCalls).put("duration_ms",(System.nanoTime()-started)/1_000_000)
            val raw=result!!.toString()
            receipt.put("status",result!!.getString("status")).put("summary",result);save()
            if(raw.length<=outputLimit+2000)raw else JSONObject().put("status",result!!.getString("status")).put("run_id",id).put("truncated",true)
                .put("result_incomplete",result!!.getString("status")!="ok").put("preview",raw.take(outputLimit/2)+"\n[omitted]\n"+raw.takeLast(outputLimit/2))
                .put("notice","Read the complete scoped receipt with program_run action=read; do not replay the program.").toString()
        } catch(cancel:CancellationException) {
            receipt.put("status","cancelled");withContext(NonCancellable){save()};throw cancel
        } catch(e:Exception) {
            result=JSONObject().put("status","error").put("run_id",id).put("rpc_calls",callCount).put("failed_calls",failures)
                .put("message",e.message?.take(1200) ?: "Program execution failed.")
            receipt.put("status","error").put("summary",result);save();result.toString()
        }
    }
}
