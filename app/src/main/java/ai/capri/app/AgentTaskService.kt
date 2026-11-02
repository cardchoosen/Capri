package ai.capri.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Protects only user-started active turns; process death never automatically replays tools. */
class AgentTaskService:Service() {
    companion object {
        private const val CHANNEL="capri_agent_tasks"
        private const val NOTICE=31_000_003
        private const val STOP="ai.capri.app.agent.STOP"
    }
    private var engine:AgentTaskEngine?=null
    private var ready=false
    private var stopping=false
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){
        super.onCreate()
        engine=AgentRuntime.current()?.taskEngine
        if(engine==null){stopSelf();return}
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Agent 任务",NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(this,NOTICE,notification(),if(Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            ready=true;engine?.attach(this)
        }catch(error:Exception){engine?.failPromotion(error);stopSelf()}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP)engine?.cancelAll()
        else if(!ready || stopping)engine?.failPromotion(IllegalStateException("Foreground service is not ready."))
        else intent?.getStringExtra("chat")?.let{engine?.attach(this);engine?.acknowledge(it)}
        update()
        return START_NOT_STICKY
    }
    internal fun update(){
        if(engine?.jobs.isNullOrEmpty()) { stopping=true;engine?.releaseIdleService(this);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
        else if(ready && !stopping)getSystemService(NotificationManager::class.java).notify(NOTICE,notification())
    }
    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,0,Intent(this,AgentTaskService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val count=engine?.jobs?.size?:0
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_capri)
            .setContentTitle(if(count>1)"Capri · $count 个任务运行中" else "Capri · 正在执行任务")
            .setContentText("切换应用后继续运行 · 点击返回 Capri").setContentIntent(open)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .addAction(0,if(count>1)"停止全部任务" else "停止任务",stop).build()
    }
    override fun onDestroy(){engine?.detach(this);super.onDestroy()}
}
