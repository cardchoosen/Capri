package ai.capri.app.agent.reliability

import ai.capri.app.*
import ai.capri.app.agent.ModelTransport
import android.app.job.*
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Android owns wakeups; application state owns occurrence identity and retry decisions. No exact alarms. */
internal object ReliabilityAndroid {
    private const val PERIODIC=90701
    private const val WAKE=90702
    fun events(context:Context)=AgentEvents(File(context.filesDir,"agent-events"))
    fun jobs(context:Context)=AgentJobs(File(context.filesDir,"agent-jobs"),events(context))
    fun schedule(context:Context,immediate:Boolean=false):Boolean {
        val jobs=jobs(context)
        if(jobs.scopes().isEmpty())return true
        val scheduler=context.getSystemService(JobScheduler::class.java)
        val component=ComponentName(context,AgentJobService::class.java)
        val periodic=JobInfo.Builder(PERIODIC,component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(900000).build()
        var accepted=true
        if(scheduler.getPendingJob(PERIODIC)==null)accepted=scheduler.schedule(periodic)==JobScheduler.RESULT_SUCCESS
        // One coalesced wakeup. Periodic reconciliation remains the fallback if Android defers it.
        val next=if(immediate)System.currentTimeMillis() else jobs.nextWakeAt()
        if(next!=null && scheduler.getPendingJob(WAKE)==null)accepted=(scheduler.schedule(JobInfo.Builder(WAKE,component).setMinimumLatency(if(immediate)0 else (next-System.currentTimeMillis()).coerceAtLeast(60000)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())==JobScheduler.RESULT_SUCCESS) && accepted
        return accepted
    }
    fun publish(context:Context,scope:String,kind:String,sourceId:String,state:String) {
        events(context).emit(scope,kind,JSONObject().put("source_id",sourceId).put("state",state))
        schedule(context,true)
    }
    fun cancelAll(context:Context) { val scheduler=context.getSystemService(JobScheduler::class.java);scheduler.cancel(PERIODIC);scheduler.cancel(WAKE) }
}

class AgentJobService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val runs=java.util.concurrent.ConcurrentHashMap<Int,Job>()
    override fun onStartJob(params:JobParameters):Boolean {
        if(runs.containsKey(params.jobId))return true
        val job=scope.launch(start=CoroutineStart.LAZY) {
            var reschedule=true
            try {
                if(SharedMemoryReset.pending(this@AgentJobService) || !LegacySessionReset.marker(this@AgentJobService).isFile) { reschedule=false;return@launch }
                val store=Store(this@AgentJobService);val jobs=ReliabilityAndroid.jobs(this@AgentJobService);val events=ReliabilityAndroid.events(this@AgentJobService)
                val files=ai.capri.app.files.WorkspaceFileTools(store.workspace)
                val delegates=ai.capri.app.agent.delegation.DelegateEngine(File(filesDir,"agent-delegates"),files)
                val runner=JobRunner(jobs,events,delegates)
                val transport=ModelTransport(OkHttpClient.Builder().addInterceptor(ModelTransport.QueueTiming()).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(120,TimeUnit.SECONDS).build())
                val sessions=store.load()
                // One bounded occurrence per OS wake. Never catch up missed recurring intervals in a burst.
                for(context in jobs.scopes()) {
                    val chat=HistoryEditing.scope(context).first
                    val owner=sessions.firstOrNull { it.id==chat && HistoryEditing.actualContextKey(it)==context }
                    if(owner==null) { jobs.requireReview(context,"The owning history changed or was removed; recreate the goal in the active context.");continue }
                    if(File(filesDir,"session-deletions/$chat.json").exists())continue
                    val ready=jobs.due(context).firstOrNull { it.optBoolean("allow_background") && it.getString("mode")=="analysis" && (it.optString("trigger")!="manual" || it.optBoolean("explicit_run")) }?:continue
                    val config=store.config(owner.id);if(config.key.isBlank() || config.model.isBlank()) { jobs.requireReview(context,"Model configuration is unavailable; review before re-creating the goal.");continue }
                    val trace=PerformanceTrace(File(store.workspace.root(chat),"metrics/job-${ready.getString("id")}-${System.currentTimeMillis()}.jsonl"))
                    var outcome=PerformanceTrace.Status.ERROR
                    try {
                        val result=runner.run(context,ready.getString("id"),config,ruleSnapshot=ai.capri.app.agent.rules.RulesStore(File(filesDir,"agent-rules")).snapshot(chat)) { messages,tools,output,budget,gate ->
                            var attempt=0
                            budget.execute(ModelRequests.Purpose.DELEGATION,beforeAttempt={ n -> gate(n);attempt=n },onBackoff={ elapsed -> trace.event(PerformanceTrace.Stage.MODEL_BACKOFF,elapsed,PerformanceTrace.Status.RETURNED,purpose=ModelRequests.Purpose.DELEGATION) }) {
                                val started=PerformanceTrace.now();var status=PerformanceTrace.Status.ERROR
                                try { transport.request(config,messages,tools,output,trace,attempt,false,ModelRequests.Purpose.DELEGATION).also { status=PerformanceTrace.Status.OK } }
                                catch(cancel:CancellationException) { status=PerformanceTrace.Status.CANCELLED;throw cancel }
                                finally { trace.event(PerformanceTrace.Stage.MODEL_REQUEST,PerformanceTrace.elapsed(started),status,attempt=attempt,purpose=ModelRequests.Purpose.DELEGATION) }
                            }
                        }
                        outcome=if(result.optString("status")=="ok")PerformanceTrace.Status.OK else PerformanceTrace.Status.INCOMPLETE
                    } finally { trace.finish(outcome) }
                    break
                }
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) { android.util.Log.w("CapriAgentJobs","Background scheduling failed; durable receipts retained. category=${error.javaClass.simpleName}") }
            finally { val owned=runs.remove(params.jobId,currentCoroutineContext().job);if(owned)jobFinished(params,false);if(owned && reschedule)runCatching { ReliabilityAndroid.schedule(this@AgentJobService) } }
        }
        runs[params.jobId]=job;job.start();return true
    }
    override fun onStopJob(params:JobParameters):Boolean { runs.remove(params.jobId)?.cancel();return false }
    override fun onDestroy() { scope.cancel();super.onDestroy() }
}
