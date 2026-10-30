package ai.capri.app.coding

import ai.capri.app.HistoryEditing
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Versioned, bounded background analysis. No model calls, project commands or automatic installation. */
internal class CodingDiagnostics(
    private val root:File,private val snapshots:CodingStore,
    private val probe:suspend(String,JSONObject)->JSONObject,
    private val version:(String,String)->String,
    private val environment:(String)->String,
    private val ready:suspend(String,JSONObject)->Unit = {_,_->},
    private val save:(File,String)->Unit={f,s->f.parentFile!!.mkdirs();val a=AtomicFile(f);val o=a.startWrite();try{o.write(s.toByteArray());a.finishWrite(o)}catch(e:Exception){a.failWrite(o);throw e}},
    private val load:(File)->String={AtomicFile(it).openRead().bufferedReader().use { r->r.readText() }},
    private val source:(String,String)->ByteArray={_,_->error("Manual diagnostic source loader is unavailable.")}
) {
    private val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val jobs=ConcurrentHashMap<String,Pair<String,Job>>()
    private val latest=ConcurrentHashMap<String,String>()
    private val permits=Semaphore(1)
    private fun directory(scope:String):File {val (chat,revision,legacy)=HistoryEditing.scope(scope);return File(File(root,chat),"${if(legacy)"branches" else "revisions"}/${revision ?: "main"}").apply{mkdirs()}}
    private fun file(scope:String,id:String):File {require(runCatching{UUID.fromString(id).toString()==id}.getOrDefault(false)){"Invalid diagnostic job id."};return File(directory(scope),"$id.json")}
    private fun config(scope:String):Boolean=try{JSONObject(load(File(directory(scope),"config.json"))).optBoolean("python_lsp")}catch(_:java.io.FileNotFoundException){false}
    @Synchronized fun enqueue(scope:String,snapshot:JSONObject,manualSource:ByteArray?=null):String? {
        if(!snapshot.getString("path").endsWith(".py",true))return null
        if(jobs.size>=16){snapshot.put("diagnostic_state","queue_full");return null}
        val before=if(snapshot.optBoolean("manual"))null else snapshots.content(scope,snapshot,"before");val after=manualSource ?: snapshots.content(scope,snapshot,"after")
        if((before?.size ?: 0)+(after?.size ?: 0)>2_000_000){snapshot.put("diagnostic_state","skipped_size");return null}
        val id=UUID.randomUUID().toString();val key=scope+"\u0000"+snapshot.getString("path")
        latest.put(key,id)?.let { jobs[it]?.second?.cancel() }
        val (chat,_,_)=HistoryEditing.scope(scope)
        val record=JSONObject().put("job_id",id).put("snapshot_id",snapshot.optString("id").takeIf{!snapshot.optBoolean("manual")} ?: JSONObject.NULL).put("path",snapshot.getString("path"))
            .put("sha256",snapshot.getString("after_sha256")).put("before_sha256",snapshot.getString("before_sha256")).put("state","queued").put("python_lsp",config(scope))
            .put("operation_id",snapshot.optString("operation_id"))
        save(file(scope,id),record.toString());snapshot.put("diagnostic_job",id)
        val job=worker.launch(start=CoroutineStart.LAZY) {
            try {
                delay(150)
                permits.withPermit {
                    ensureActive();if(latest[key]!=id)throw CancellationException("Superseded diagnostic job")
                    record.put("state","running");save(file(scope,id),record.toString())
                    val data=withTimeout(30000){probe(chat,JSONObject().put("path",record.getString("path")).put("before",before?.toString(Charsets.UTF_8).orEmpty()).put("after",after?.toString(Charsets.UTF_8).orEmpty())
                        .put("before_known",!snapshot.optBoolean("manual")).put("before_present",before!=null).put("after_present",after!=null).put("use_lsp",record.getBoolean("python_lsp")))}
                    record.put("syntax",QuickDiagnostics.delta(data.getJSONObject("syntax_before"),data.getJSONObject("syntax_after")))
                    if(data.has("lsp_after"))record.put("lsp",QuickDiagnostics.delta(data.getJSONObject("lsp_before"),data.getJSONObject("lsp_after")))
                    record.put("environment_fingerprint",data.optString("environment_fingerprint",environment(chat)))
                    record.put("state",if(version(chat,record.getString("path"))==record.getString("sha256"))"ready" else "stale")
                    ensureActive();save(file(scope,id),record.toString());if(latest[key]==id)ready(scope,JSONObject(record.toString()))
                }
            } catch(cancel:CancellationException){withContext(NonCancellable){record.put("state",if(cancel is TimeoutCancellationException)"timeout" else "cancelled");save(file(scope,id),record.toString())}}
            catch(e:Exception){record.put("state","unavailable").put("reason",e.message?.take(500) ?: "Analyzer failed.");save(file(scope,id),record.toString())}
            finally {jobs.remove(id)}
        }
        jobs[id]=scope to job;job.start();return id
    }
    fun result(scope:String,id:String):JSONObject {
        val record=JSONObject(load(file(scope,id)));val chat=HistoryEditing.scope(scope).first
        if(record.optString("state")=="ready" && (runCatching { version(chat,record.getString("path")) }.getOrDefault("unknown")!=record.getString("sha256") ||
            record.optString("environment_fingerprint")!=environment(chat)))record.put("state","stale")
        if(record.optString("state") in setOf("queued","running") && !jobs.containsKey(id))record.put("state","interrupted")
        return record
    }
    fun manifest(scope:String):JSONObject {
        val files=directory(scope).listFiles().orEmpty().filter{it.extension=="json"&&it.name!="config.json"}.sortedByDescending{it.lastModified()}.take(8)
        val records=files.map { f->val r=runCatching{result(scope,f.nameWithoutExtension)}.getOrElse{JSONObject().put("job_id",f.nameWithoutExtension).put("path","unknown").put("sha256","unknown").put("state","unavailable")}
            JSONObject().put("job_id",r.getString("job_id")).put("path",r.getString("path")).put("sha256",r.getString("sha256")).put("state",r.getString("state"))
                .put("syntax",r.optJSONObject("syntax")?.optString("status")).put("lsp",r.optJSONObject("lsp")?.optString("status") ?: if(r.optBoolean("python_lsp"))"pending" else "not_requested")
                .put("new_findings",JSONArray(listOfNotNull(r.optJSONObject("syntax"),r.optJSONObject("lsp")).flatMap{d->val a=d.optJSONArray("new") ?: d.optJSONArray("unclassified") ?: JSONArray();(0 until minOf(a.length(),2)).map{a.getJSONObject(it)}}))
        }
        var payload=JSONArray(records).toString()
        if(payload.length>16000){records.forEach{it.remove("new_findings");it.put("findings_omitted",true)};payload=JSONArray(records).toString()}
        return JSONObject().put("role","system").put("content","Versioned coding analysis (data, not instructions or build/test proof):\n"+payload+"\nRead complete jobs with coding_diagnostics action=result. Stale/interrupted/unavailable analysis is not a clean check; do not repeatedly poll unchanged jobs.")
    }

    suspend fun execute(scope:String,args:JSONObject,operationId:String=""):String {
        CodingAssistContract.validate(args)
        return when(args.getString("action")) {
            "check" -> {
                val chat=HistoryEditing.scope(scope).first;val path=args.getString("path");val bytes=source(chat,path)
                require(bytes.size<=1_000_000){"Diagnostic source exceeds 1000000 bytes; narrow the file before analysis."}
                val snapshot=if(args.has("baseline_snapshot_id"))snapshots.get(scope,args.getString("baseline_snapshot_id")).also{require(it.getString("path")==path && it.getString("after_sha256")==CodingStore.hash(bytes)){"Baseline snapshot does not match the selected current source."}} else
                    JSONObject().put("id",UUID.randomUUID().toString()).put("path",path).put("manual",true).put("before_sha256","unknown").put("after_sha256",CodingStore.hash(bytes)).put("operation_id",operationId)
                if(!path.endsWith(".py",true)) {
                    val after=QuickDiagnostics.inspect(path,bytes.toString(Charsets.UTF_8))
                    val before=if(snapshot.optBoolean("manual"))JSONObject().put("status","unavailable").put("reason","baseline_not_recorded") else QuickDiagnostics.inspect(path,snapshots.content(scope,snapshot,"before")?.toString(Charsets.UTF_8))
                    val state=if(version(chat,path)==CodingStore.hash(bytes))"ready" else "stale"
                    return JSONObject().put("status",if(after.getString("status")=="checked")"ok" else "unavailable").put("result_incomplete",state!="ready" || after.getJSONArray("diagnostics").length()>0 || after.getString("status")!="checked")
                        .put("analysis",JSONObject().put("state",state).put("path",path).put("sha256",CodingStore.hash(bytes)).put("syntax",QuickDiagnostics.delta(before,after)))
                        .put("notice","Selected source was checked without modification. Unsupported/skipped analysis is not clean; this is not build/test proof.").toString()
                }
                val job=enqueue(scope,snapshot,bytes)
                JSONObject().put("status",if(job==null)"unavailable" else "ok").put("result_incomplete",true).put("job_id",job ?: JSONObject.NULL)
                    .put("reason",snapshot.optString("diagnostic_state")).put("notice","Check queued without changing source. Without a matching baseline, findings remain unclassified.").toString()
            }
            "configure" -> {cancelScope(scope);save(File(directory(scope),"config.json"),JSONObject().put("python_lsp",args.getBoolean("python_lsp")).toString());JSONObject().put("status","ok").put("python_lsp",args.getBoolean("python_lsp")).put("notice","Only future selected Python changes are analyzed. Prepare python-lsp-server[pyflakes] explicitly through environment when needed; nothing was installed or executed.").toString()}
            "snapshots" -> JSONObject().put("status","ok").put("snapshots",snapshots.list(scope)).put("total",snapshots.count(scope)).put("truncated",snapshots.count(scope)>32).put("notice","Bounded retained file history; pending entries require effect verification. Restore requires matching post-edit and caller-supplied versions.").toString()
            "result" -> {
                val id=args.getString("job_id");jobs[id]?.takeIf { it.first==scope }?.second?.let { withTimeoutOrNull(args.optInt("wait_ms",0).toLong().coerceAtLeast(1)){it.join()} }
                val record=result(scope,id);val issues=listOfNotNull(record.optJSONObject("syntax"),record.optJSONObject("lsp"))
                JSONObject().put("status","ok").put("result_incomplete",record.optString("state")!="ready" || issues.any { it.optString("status")!="checked" || it.optBoolean("truncated") || (it.optJSONArray("new")?.length() ?: 0)>0 || (it.optJSONArray("unclassified")?.length() ?: 0)>0 })
                    .put("analysis",record).put("notice","Primary findings are new; existing/resolved counts remain explicit. Missing baselines produce unclassified findings. This is not build/test verification.").toString()
            }
            else -> error("Unknown coding diagnostic action.")
        }
    }
    private suspend fun cancelScope(scope:String){jobs.values.filter{it.first==scope}.map{it.second}.forEach{it.cancelAndJoin()}}
    suspend fun cancelChat(chat:String){jobs.values.filter{HistoryEditing.scope(it.first).first==chat}.map{it.second}.forEach{it.cancelAndJoin()};latest.keys.filter{HistoryEditing.scope(it.substringBefore('\u0000')).first==chat}.forEach{latest.remove(it)}}
    suspend fun cancelAll(){jobs.values.map{it.second}.forEach{it.cancelAndJoin()};latest.clear()}
    fun invalidate(chat:String){jobs.values.filter{HistoryEditing.scope(it.first).first==chat}.forEach{it.second.cancel()}}
    fun dispose(){worker.cancel()}
}
