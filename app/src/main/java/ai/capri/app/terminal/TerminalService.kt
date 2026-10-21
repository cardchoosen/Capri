package ai.capri.app.terminal

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ai.capri.app.MainActivity
import ai.capri.app.R

/** User-started local terminal service; never auto-recreates processes after Android kills them. */
class TerminalService : Service() {
    companion object {
        const val STOP = "ai.capri.app.terminal.STOP"
        private const val CHANNEL = "capri_terminals"
        private const val NOTICE = 31_000_001
    }
    private val manager get() = TerminalManager.get(this)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "持续终端", NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(this, NOTICE, notification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            manager.attach(this)
        } catch (error: Exception) {
            manager.serviceFailure = error.message ?: "前台终端服务启动失败"
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) manager.stopFromNotification()
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TerminalService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_capri)
            .setContentTitle("Capri · 持续终端").setContentText("本地终端运行中，点击返回 Capri")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, "关闭全部终端", stop).build()
    }
    override fun onDestroy() {
        manager.detach(this)
        super.onDestroy()
    }
}
