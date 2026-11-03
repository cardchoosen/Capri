package ai.capri.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

/** User-visible kernel host. Idle clients remain available; only active tasks hold a bounded CPU lease. */
class AgentTaskService:Service() {
    companion object {
        internal const val NOTICE=31_000_003
        internal const val STOP_TASKS="ai.capri.app.agent.STOP"
        internal const val STOP_HOST="ai.capri.app.kernel.STOP"
        private const val CHANNEL="capri_agent_tasks"
        private const val WAKE_TIMEOUT=10*60*1000L
    }
    private val shutdown=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var engine:AgentTaskEngine?=null
    private var ready=false
    private var stopping=false
    private var wake:PowerManager.WakeLock?=null
    private val handler=Handler(Looper.getMainLooper())
    private val renew=object:Runnable{override fun run(){if(ready && !stopping && engine?.jobs?.isNotEmpty()==true){wake?.acquire(WAKE_TIMEOUT);handler.postDelayed(this,WAKE_TIMEOUT/2)}}}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){
        super.onCreate()
        if(!KernelHost.enabled(this)){stopSelf();return}
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Capri 内核",NotificationManager.IMPORTANCE_LOW))
        try {
            // Promote before loading history or initializing adapters; no Activity is required.
            ServiceCompat.startForeground(this,NOTICE,notification(),if(Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            engine=AgentRuntime.get(application).taskEngine
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Capri:agent-kernel").apply{setReferenceCounted(false)}
            ready=true;engine?.attach(this);update()
        }catch(error:Exception){android.util.Log.w("CapriKernel","host_initialization_failed type=${error.javaClass.simpleName}");engine?.failPromotion(error);stopSelf()}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP_HOST){
            KernelHost.disable(this);stopping=true;engine?.cancelAll()
            shutdown.launch{
                withTimeoutOrNull(10_000){engine?.jobs?.values?.toList()?.joinAll()}
                releaseWake();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }
            return START_NOT_STICKY
        }
        if(!ready || stopping){engine?.failPromotion(IllegalStateException("Kernel host is not ready."));stopSelf();return START_NOT_STICKY}
        if(intent?.action==STOP_TASKS)engine?.cancelAll()
        else intent?.getStringExtra("chat")?.let{engine?.acknowledge(it)}
        update()
        // Recreate the idle host after process loss; persisted task effects are never replayed.
        return if(KernelHost.enabled(this))START_STICKY else START_NOT_STICKY
    }
    internal val cpuLeaseHeld get()=wake?.isHeld==true
    internal val available get()=ready && !stopping
    internal fun admit():Boolean {
        if(!ready || stopping)return false
        update();return true
    }
    internal fun update(){
        if(!ready || stopping)return
        if(engine?.jobs?.isNotEmpty()==true){
            if(wake?.isHeld!=true){wake?.acquire(WAKE_TIMEOUT);handler.removeCallbacks(renew);handler.postDelayed(renew,WAKE_TIMEOUT/2)}
        }else releaseWake()
        getSystemService(NotificationManager::class.java).notify(NOTICE,notification())
    }
    private fun releaseWake(){handler.removeCallbacks(renew);wake?.takeIf{it.isHeld}?.release()}
    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(name:String,id:Int)=PendingIntent.getService(this,id,Intent(this,AgentTaskService::class.java).setAction(name),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val count=engine?.jobs?.size?:0
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_capri)
            .setContentTitle(if(count>0)"Capri · $count 个任务运行中" else "Capri · 内核待命")
            .setContentText(if(count>0)"黑屏及切换应用后继续执行 · 点击返回" else "手机与 Web 可连接 · 点击返回")
            .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .apply{if(count>0)addAction(0,"停止任务",action(STOP_TASKS,0))}
            .addAction(0,"关闭内核",action(STOP_HOST,1)).build()
    }
    override fun onDestroy(){ready=false;shutdown.cancel();releaseWake();engine?.detach(this);super.onDestroy()}
}
