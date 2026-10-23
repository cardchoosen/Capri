package ai.capri.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

/** Created with a chat, never an autonomous boot-time environment installer. */
class LinuxSetupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending = 0
    private val holds = mutableSetOf<String>()
    private lateinit var wake: PowerManager.WakeLock
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("capri_environment", "执行环境准备", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ServiceCompat.startForeground(this, 31_000_002, NotificationCompat.Builder(this, "capri_environment")
            .setSmallIcon(R.drawable.ic_capri).setContentTitle("Capri · 正在准备执行环境")
            .setContentText("准备完成后自动继续").setContentIntent(open).setOngoing(true).setSilent(true).build(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Capri:environment").apply {
            setReferenceCounted(false); acquire(25 * 60 * 1000L)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra("lease")
        if (intent?.action == "hold" && token != null) { holds += token; return START_NOT_STICKY }
        if (intent?.action == "release" && token != null) { holds -= token; if (holds.isEmpty() && pending == 0) stopSelf(); return START_NOT_STICKY }
        val chat = intent?.getStringExtra("chat")
        if (chat == null || !chat.matches(Regex("[a-zA-Z0-9-]+"))) { if (pending == 0 && holds.isEmpty()) stopSelf(); return START_NOT_STICKY }
        pending++
        scope.launch {
            try {
                val runtime = PythonRuntime(this@LinuxSetupService, Workspace(java.io.File(filesDir, "sessions")))
                if (intent.getBooleanExtra("base_only", false)) runtime.environment.ensureBase() else runtime.ensureSession(chat)
            }
            catch (_: Exception) { /* Environment state carries the bounded failure. */ }
            finally { pending--; if (pending == 0 && holds.isEmpty()) stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); if (::wake.isInitialized && wake.isHeld) wake.release(); super.onDestroy() }
}

internal object EnvironmentForeground {
    fun hold(context: android.content.Context): String {
        val token = java.util.UUID.randomUUID().toString()
        androidx.core.content.ContextCompat.startForegroundService(context,
            Intent(context, LinuxSetupService::class.java).setAction("hold").putExtra("lease", token))
        return token
    }
    fun release(context: android.content.Context, token: String) {
        runCatching { context.startService(Intent(context, LinuxSetupService::class.java).setAction("release").putExtra("lease", token)) }
    }
}
