package ai.capri.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ai.capri.core.KernelTasks
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Android admission adapter around host-independent task ownership. */
internal class AgentTaskEngine(private val context:Context,private val changed:()->Unit) {
    class ForegroundUnavailable(cause:Throwable):IllegalStateException("Agent kernel foreground host is unavailable; no task effects were started.",cause)
    private val promotions=ConcurrentHashMap<String,CompletableDeferred<Unit>>()
    private var service:AgentTaskService?=null
    private val tasks=KernelTasks(Dispatchers.Main.immediate){key->promotions.remove(key)?.cancel();service?.update();changed()}
    val jobs get()=tasks.jobs
    fun create(chat:String,block:suspend ()->Unit):Job=tasks.create(chat,block)
    suspend fun promote(chat:String) {
        check(jobs[chat]===currentCoroutineContext().job) { "Foreground admission requires the owning task." }
        try {
            check(KernelHost.enabled(context)) { "Kernel was stopped by the user." }
            // An already running standby host can admit Web work without a background service launch.
            service?.takeIf{it.admit()}?.let{return}
            val ready=CompletableDeferred<Unit>();promotions[chat]=ready
            try {
                ContextCompat.startForegroundService(context,Intent(context,AgentTaskService::class.java).putExtra("chat",chat))
                if(withTimeoutOrNull(10_000){ready.await();true}!=true)error("Foreground admission timed out.")
            }finally{promotions.remove(chat,ready)}
        }catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){throw ForegroundUnavailable(error)}
    }
    internal val cpuLeaseHeld get()=service?.cpuLeaseHeld==true
    internal val hostAvailable get()=service?.available==true
    fun attach(value:AgentTaskService){service=value}
    fun acknowledge(chat:String){promotions[chat]?.complete(Unit)}
    fun failPromotion(error:Exception){promotions.values.forEach{it.completeExceptionally(error)}}
    fun detach(value:AgentTaskService){if(service===value){service=null;cancelAll()}}
    fun cancelAll(){tasks.cancelAll()}
    fun dispose(){tasks.close()}
}
