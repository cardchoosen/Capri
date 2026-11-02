package ai.capri.app.extensions.hub

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*

internal object HubAndroid {
    private const val PERIODIC=90711;private const val STARTUP=90712
    fun schedule(context:Context,force:Boolean=false) {
        val hub=PublicSkillHub.get(context);if(!hub.enabled())return
        val scheduler=context.getSystemService(JobScheduler::class.java);val component=ComponentName(context,PublicSkillJobService::class.java)
        if(scheduler.getPendingJob(PERIODIC)==null)scheduler.schedule(JobInfo.Builder(PERIODIC,component).setPeriodic(6*60*60*1000L).setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
        val last=hub.status().optLong("last_attempt")
        if((force || System.currentTimeMillis()-last>60*60*1000L) && scheduler.getPendingJob(STARTUP)==null)scheduler.schedule(JobInfo.Builder(STARTUP,component).setMinimumLatency(0).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
    }
    fun cancel(context:Context) { val scheduler=context.getSystemService(JobScheduler::class.java);scheduler.cancel(PERIODIC);scheduler.cancel(STARTUP) }
}
class PublicSkillJobService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);private val jobs=java.util.concurrent.ConcurrentHashMap<Int,Job>()
    override fun onStartJob(params:JobParameters):Boolean {
        if(jobs.containsKey(params.jobId))return true
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try { withTimeout(120000) { PublicSkillHub.get(this@PublicSkillJobService).sync() } }
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){ /* Keep the previous complete cache; next scheduled refresh can retry. */ }
            finally { if(jobs.remove(params.jobId,currentCoroutineContext().job))jobFinished(params,false) }
        }
        jobs[params.jobId]=job;job.start();return true
    }
    override fun onStopJob(params:JobParameters):Boolean { jobs.remove(params.jobId)?.cancel();return false }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
