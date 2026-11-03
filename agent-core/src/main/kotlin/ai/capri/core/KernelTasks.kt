package ai.capri.core

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Host-independent task ownership. A trigger supplies an ownership key, never a UI lifecycle. */
class KernelTasks(dispatcher:CoroutineDispatcher,private val finished:(String)->Unit) {
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    val jobs=ConcurrentHashMap<String,Job>()
    fun create(key:String,work:suspend ()->Unit):Job {
        require(key.isNotBlank()) { "Task ownership key is required." }
        val job=scope.launch(start=CoroutineStart.LAZY){work()}
        if(jobs.putIfAbsent(key,job)!=null){job.cancel();error("A task already owns this key.")}
        job.invokeOnCompletion{if(jobs.remove(key,job))scope.launch(NonCancellable){finished(key)}}
        return job
    }
    fun cancelAll(){jobs.values.toList().forEach{it.cancel()}}
    fun close(){scope.cancel()}
}
