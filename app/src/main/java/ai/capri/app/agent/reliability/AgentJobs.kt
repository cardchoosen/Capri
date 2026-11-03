package ai.capri.app.agent.reliability

import ai.capri.app.HistoryEditing
import ai.capri.app.knowledge.MemoryPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Durable goals and bounded analysis occurrences. Persist claim before any model call; never replay unknown runs. */
internal class AgentJobs(private val root:File,private val events:AgentEvents,private val clock:()->Long=System::currentTimeMillis) {
    companion object { private val live=ConcurrentHashMap.newKeySet<String>();private val signals=ConcurrentHashMap<String,MutableStateFlow<Long>>();val eventKinds=setOf("process_completed","coding_ready","turn_finished") }
    private fun store(scope:String)=DurableJson(File(root,"${HistoryEditing.scope(scope).first}/jobs.json"))
    private fun values(data:JSONObject)=data.optJSONArray("jobs")?:JSONArray().also { data.put("jobs",it) }
    private fun find(data:JSONObject,scope:String,id:String):JSONObject=(0 until values(data).length()).map { values(data).getJSONObject(it) }.firstOrNull { it.optString("scope")==scope && it.optString("id")==id }?:error("Job not found in the current scope.")
    private fun key(scope:String,id:String)="${root.canonicalPath}/$scope/$id"
    fun changes(scope:String,id:String):StateFlow<Long> = signals.getOrPut(key(scope,id)) { MutableStateFlow(0L) }
    private fun signal(scope:String,id:String) { signals.getOrPut(key(scope,id)) { MutableStateFlow(0L) }.update { it+1 } }
    fun create(scope:String,args:JSONObject,modelScope:String,entryId:String,instructions:JSONArray=JSONArray(),checkpointNode:String=""):JSONObject = store(scope).access { data ->
        JobContract.validate(args)
        val jobs=values(data);val request=args.getString("request_key");val hash=MemoryPolicy.hash(ai.capri.app.agent.delegation.DelegateContract.canonical(args))
        for(i in 0 until jobs.length())jobs.getJSONObject(i).let { old -> if(old.optString("scope")==scope && old.optString("request_key")==request) { require(old.getString("request_hash")==hash) { "Job request key conflicts with existing arguments." };return@access JSONObject(old.toString()).put("reused",true) } }
        require(jobs.length()<128) { "Job capacity reached; use another task chat. Idempotency receipts are retained." }
        val mode=args.getString("mode");val trigger=args.getString("trigger");val background=args.optBoolean("allow_background")
        require(mode in setOf("checkpoint","analysis") && trigger in setOf("manual","once","interval","event"))
        require(!background || mode=="analysis") { "Only isolated read-only analysis can run unattended." }
        require(trigger!="interval" || args.optLong("interval_ms") in 900000..2592000000L) { "Recurring jobs require an interval of 15 minutes to 30 days." }
        require(trigger !in setOf("once","interval") || args.optLong("due_at") in clock()..clock()+31536000000L) { "due_at must be within the next year." }
        require(trigger!="event" || args.optString("event_kind") in eventKinds) { "Unsupported job event." }
        require(mode!="analysis" || (args.optJSONArray("files")?.length()?:0) in 1..16) { "Analysis requires 1..16 exact workspace files." }
        args.optJSONArray("files")?.let { files -> for(i in 0 until files.length())ai.capri.app.agent.delegation.DelegateWorkspace.normalize(files.getString(i)) }
        require(instructions.toString().length<=8000) { "Saved requirements exceed the bounded handoff; keep this goal in the foreground." }
        val cursor=events.read(scope).getLong("latest")
        val job=JSONObject(args.toString()).put("id",UUID.randomUUID().toString()).put("scope",scope).put("model_scope",modelScope).put("owner_entry",entryId).put("instructions",JSONArray(instructions.toString())).put("checkpoint_node",checkpointNode).put("request_hash",hash)
            .put("state",if(trigger=="manual")"ready" else "waiting").put("created_at",clock()).put("event_cursor",cursor).put("occurrence",0).put("failures",0)
        jobs.put(job);JSONObject(job.toString())
    }
    fun list(scope:String):JSONArray=store(scope).access { data -> JSONArray((0 until values(data).length()).map { values(data).getJSONObject(it) }.filter { it.getString("scope")==scope }.map { JSONObject(it.toString()).apply { put("title",optString("goal").take(100));remove("goal");remove("instructions");remove("files");remove("model_scope");remove("request_hash");remove("last_result") } }) }
    fun read(scope:String,id:String):JSONObject=store(scope).access { JSONObject(find(it,scope,id).toString()) }
    fun change(scope:String,id:String,action:String):JSONObject=store(scope).access { data ->
        val job=find(data,scope,id)
        when(action) {
            "pause","cancel" -> { val target=if(action=="pause")"paused" else "cancelled";if(job.getString("state")!="cancelled" && job.getString("state")!=target) { job.put("state",target);job.put("generation",job.optLong("generation")+1) } }
            "resume" -> { require(key(scope,id) !in live) { "The previous occurrence is still stopping; inspect its receipt before resuming." };require(job.getString("state")=="paused") { "Only paused jobs can resume; interrupted occurrences require explicit run." };job.put("state",if(job.getString("trigger")=="manual")"ready" else "waiting") }
            "run" -> { require(key(scope,id) !in live) { "The previous occurrence is still stopping; inspect its receipt before rerunning." };require(job.getString("state")!in setOf("running","cancelled")) { "Running or cancelled jobs cannot start again." };job.put("state","ready");job.put("explicit_run",true) }
            else -> error("Unknown job action.")
        }
        JSONObject(job.toString())
    }.also { signal(scope,id) }
    /** Event cursors advance on every scan. Retention gaps require review, never inferred delivery. */
    fun due(scope:String):List<JSONObject> = store(scope).access { data ->
        val result=mutableListOf<JSONObject>()
        for(i in 0 until values(data).length()) {
            val job=values(data).getJSONObject(i);if(job.getString("scope")!=scope)continue
            val id=job.getString("id")
            if(job.optString("state")=="running" && key(scope,id) !in live) { job.put("state","interrupted").put("reason","Application execution ended. Inspect retained evidence; no occurrence was replayed.") }
            if(job.optString("state")=="waiting") {
                val trigger=job.getString("trigger")
                if(trigger=="event") {
                    val page=events.read(scope,job.optLong("event_cursor"));job.put("event_cursor",page.getLong("cursor"))
                    if(page.getBoolean("gap"))job.put("state","needs_review").put("reason","Event history gap; inspect actual state before running.")
                    else { val a=page.getJSONArray("events");if((0 until a.length()).any { val e=a.getJSONObject(it);e.getString("kind")==job.getString("event_kind") && (job.optString("source_id").isBlank() || e.optString("source_id")==job.getString("source_id")) })job.put("state","ready") }
                } else if(trigger in setOf("once","interval") && clock()>=job.getLong("due_at"))job.put("state","ready")
            }
            if(job.optString("state")=="ready")result+=JSONObject(job.toString())
        }
        result
    }
    fun nextWakeAt():Long? = scopes().mapNotNull { scope -> store(scope).access { data ->
        (0 until values(data).length()).map { values(data).getJSONObject(it) }.filter { it.optString("scope")==scope && it.optBoolean("allow_background") && it.optString("mode")=="analysis" && (it.optString("trigger")!="manual" || it.optBoolean("explicit_run")) }.mapNotNull { job ->
            when { job.optString("state")=="ready" -> clock();job.optString("state")=="waiting" && job.optString("trigger") in setOf("once","interval") -> job.getLong("due_at");else -> null }
        }.minOrNull()
    } }.minOrNull()
    fun scopes():Set<String> = root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-zA-Z0-9-]+")) }.flatMap { chat ->
        DurableJson(File(chat,"jobs.json")).access { data -> (0 until values(data).length()).map { values(data).getJSONObject(it).getString("scope") } }
    }.toSet()
    fun claim(scope:String,id:String,currentModelScope:String,foreground:Boolean=false):JSONObject {
        var admitted=false
        try { return store(scope).access { data ->
        val job=find(data,scope,id);require(job.getString("state")=="ready") { "Job is not ready." }
        require(job.getString("mode")=="analysis" && (job.optBoolean("allow_background") || foreground && job.optBoolean("explicit_run"))) { "This goal requires foreground continuation." }
        if(job.getString("model_scope")!=currentModelScope) { job.put("state","needs_review");return@access JSONObject(job.toString()) }
        check(live.add(key(scope,id))) { "Job is already executing." };admitted=true
        job.put("state","running").put("run_id",UUID.randomUUID().toString()).put("started_at",clock()).put("occurrence",job.optInt("occurrence")+1)
        JSONObject(job.toString())
    }
        } catch(error:Exception) { if(admitted)live.remove(key(scope,id));throw error }
    }
    fun current(scope:String,claim:JSONObject):Boolean=store(scope).access { data -> val job=find(data,scope,claim.getString("id"));job.getString("state")=="running" && job.optString("run_id")==claim.optString("run_id") && job.optLong("generation")==claim.optLong("generation") }
    fun finish(scope:String,claim:JSONObject,result:JSONObject):JSONObject=try { store(scope).access { data ->
        val job=find(data,scope,claim.getString("id"));require(job.optString("run_id")==claim.getString("run_id")) { "Occurrence identity changed." }
        val cancelled=job.getString("state") in setOf("paused","cancelled") || job.optLong("generation")!=claim.optLong("generation")
        val children=result.optJSONArray("children")?:JSONArray()
        val interrupted=result.optString("error_code")=="job_interrupted" || result.optString("state")=="interrupted" || (0 until children.length()).any { children.getJSONObject(it).optString("state")=="interrupted" }
        val success=result.optString("status")=="ok" && !result.optBoolean("result_incomplete")
        job.put("last_result",JSONObject(result.toString())).put("finished_at",clock()).put("last_outcome",if(success)"completed" else "failed")
        if(!cancelled) {
            val failures=if(success)0 else job.optInt("failures")+1;job.put("failures",failures)
            if(interrupted)job.put("state","interrupted")
            else if(job.getString("trigger")=="interval" && failures<3)job.put("state","waiting").put("due_at",ai.capri.core.KernelSchedule.nextInterval(clock(),job.getLong("interval_ms")))
            else job.put("state",if(failures>=3)"needs_review" else if(success)"completed" else "failed")
        }
        JSONObject(job.toString())
    } } finally { live.remove(key(scope,claim.getString("id"))) }
    fun complete(scope:String,id:String,conclusion:JSONObject):JSONObject=store(scope).access { data ->
        require(conclusion.optString("outcome")=="completed" && (conclusion.optJSONArray("evidence")?.length()?:0)>0) { "Completed goals require a verified conclusion with evidence references." }
        val job=find(data,scope,id)
        require(job.optString("mode")=="checkpoint" && job.optString("state") !in setOf("running","cancelled")) { "Only a noncancelled foreground goal can be settled." }
        job.put("state","completed").put("finished_at",clock()).put("last_result",JSONObject(conclusion.toString()))
        JSONObject(job.toString())
    }
    fun requireReview(scope:String,reason:String) = store(scope).access { data ->
        for(i in 0 until values(data).length())values(data).getJSONObject(i).let { job -> if(job.optString("scope")==scope && job.optString("state") in setOf("ready","waiting"))job.put("state","needs_review").put("reason",reason) }
    }
    fun cancelScope(scope:String) = store(scope).access { data ->
        for(i in 0 until values(data).length())values(data).getJSONObject(i).let { job -> if(job.optString("scope")==scope)job.put("state","cancelled").put("generation",job.optLong("generation")+1) }
    }.also { signals.keys.filter { it.startsWith("${root.canonicalPath}/$scope/") }.forEach { signals[it]?.update { value -> value+1 } } }
    fun recover(scope:String) { due(scope) }
}
