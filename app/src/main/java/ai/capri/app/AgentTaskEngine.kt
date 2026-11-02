package ai.capri.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Owns task lifetimes independently of UI scopes. Foreground promotion must precede task effects. */
internal class AgentTaskEngine(private val context:Context,private val changed:()->Unit) {
    class ForegroundUnavailable(cause:Throwable):IllegalStateException("Agent task foreground service is unavailable; no task effects were started.",cause)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val jobs=ConcurrentHashMap<String,Job>()
    private val promotions=ConcurrentHashMap<String,CompletableDeferred<Unit>>()
    private var service:AgentTaskService?=null
    fun create(chat:String,block:suspend ()->Unit):Job {
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try { block() } finally {
                jobs.remove(chat,currentCoroutineContext().job)
                promotions.remove(chat)?.cancel()
                service?.update()
                changed()
            }
        }
        check(jobs.putIfAbsent(chat,job)==null) { "A task already owns this conversation." }
        return job
    }
    suspend fun promote(chat:String) {
        check(jobs[chat]===currentCoroutineContext().job) { "Foreground promotion requires the owning task." }
        val ready=CompletableDeferred<Unit>();promotions[chat]=ready
        try {
            ContextCompat.startForegroundService(context,Intent(context,AgentTaskService::class.java).putExtra("chat",chat))
            if(withTimeoutOrNull(10_000){ready.await();true}!=true)throw IllegalStateException("Foreground promotion timed out.")
        } catch(cancel:CancellationException) {
            throw cancel
        } catch(error:Exception) { throw ForegroundUnavailable(error) }
        finally { promotions.remove(chat,ready) }
    }
    fun attach(value:AgentTaskService){service=value}
    fun acknowledge(chat:String){promotions[chat]?.complete(Unit)}
    fun failPromotion(error:Exception){promotions.values.forEach{it.completeExceptionally(error)}}
    fun releaseIdleService(value:AgentTaskService){if(service===value)service=null}
    fun detach(value:AgentTaskService){if(service===value){service=null;cancelAll()}}
    fun cancelAll(){jobs.values.forEach{it.cancel()}}
    fun dispose(){scope.cancel()}
}
