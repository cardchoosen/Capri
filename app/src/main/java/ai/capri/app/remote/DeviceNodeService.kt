package ai.capri.app.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.AppOpsManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ai.capri.app.MainActivity
import ai.capri.app.Store
import ai.capri.app.nativebridge.AndroidTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** User-enabled remote executor. Its idle state owns no CPU wake lock and performs no polling. */
class DeviceNodeService : Service() {
    companion object {
        const val ACTION_STOP = "ai.capri.app.remote.DEVICE_NODE_STOP"
        private const val CHANNEL = "capri_remote_device"
        private const val NOTICE = 31_000_041
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, DeviceNodeService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, DeviceNodeService::class.java).setAction(ACTION_STOP))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopping = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    private var attempt = 0
    private val tool by lazy { AndroidTool(application) }
    private val store by lazy { Store(this) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (socket == null && !stopping.get()) { attempt = 0; connect() }
        }
        override fun onLost(network: Network) {
            socket?.let { current ->
                socket = null
                current.close(1001, "network_lost")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Capri 设备接管", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        try {
            ServiceCompat.startForeground(this, NOTICE, notification("正在连接 Capri 服务"),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            connectivity.registerDefaultNetworkCallback(networkCallback)
            if (store.remoteDeviceEnabled()) connect()
            else stopSelf()
        } catch (error: Exception) {
            android.util.Log.w("CapriDeviceNode", "startup_failed type=${error.javaClass.simpleName}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !store.remoteDeviceEnabled()) {
            store.setRemoteDeviceEnabled(false)
            stopping.set(true)
            socket?.close(1000, "user_disabled")
            scope.launch { removeDozeExemption(); stopSelf() }
            return START_NOT_STICKY
        }
        if (socket == null) connect()
        return START_STICKY
    }

    private fun connect() {
        if (stopping.get() || socket != null) return
        val config = store.remoteDeviceConfig()
        if (config == null) { stopSelf(); return }
        try {
            val chat = checkNotNull(store.remoteChatConfig())
            val certificate = chat.optString("certificate").takeIf(String::isNotEmpty)
                ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
            val client = RemoteChatClient(chat.getString("address"), chat.getString("token"), certificate)
            socket = client.connectDevice(config.getString("socket_url"), config.getString("device_token"), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                    socket = webSocket
                    announce(webSocket)
                    update("Capri 设备执行器已连接")
                    scope.launch { syncDozeExemption() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > 512 * 1024) { webSocket.close(1009, "message_too_large"); return }
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: run { webSocket.close(1007, "invalid_json"); return }
                    if (message.optString("type") == "invoke") scope.launch { execute(webSocket, message) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket === webSocket) socket = null
                    if (!stopping.get()) reconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (socket === webSocket) socket = null
                    if (!stopping.get()) reconnect()
                }
            })
        } catch (error: Exception) {
            android.util.Log.w("CapriDeviceNode", "connect_failed type=${error.javaClass.simpleName}")
            reconnect()
        }
    }

    private fun reconnect() {
        val delayMs = (2_000L shl attempt.coerceAtMost(7)).coerceAtMost(300_000L)
        attempt++
        update("设备连接中断 · ${delayMs / 1000}s 后重试")
        scope.launch { delay(delayMs); connect() }
    }

    private fun describe(): JSONObject = tool.capabilities().let { all ->
        val root = all.optJSONObject("root") ?: JSONObject()
        val actions = all.optJSONArray("actions") ?: JSONArray()
        val caps = JSONArray().put("device.describe")
        for (index in 0 until actions.length()) {
            val action = actions.getJSONObject(index)
            if (action.optString("status") != "unsupported") caps.put("android." + action.getString("action"))
        }
        JSONObject().put("name", Build.MANUFACTURER + " " + Build.MODEL).put("platform", "android")
            .put("android_version", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("root", root).put("capabilities", actions).put("limitations", all.optJSONArray("not_implemented") ?: JSONArray())
            .put("executor_capabilities", caps)
    }

    private fun announce(webSocket: WebSocket) {
        val details = describe()
        webSocket.send(JSONObject().put("type", "hello").put("protocol", "capri.device/1")
            .put("name", details.getString("name")).put("platform", "android")
            .put("version", Build.VERSION.RELEASE).put("capabilities", details.getJSONArray("executor_capabilities")).toString())
    }

    private suspend fun execute(webSocket: WebSocket, message: JSONObject) {
        val requestId = message.optString("request_id")
        val capability = message.optString("capability")
        val deadline = message.optLong("deadline_ms", 0)
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Capri:remote-device-task")
        wake.setReferenceCounted(false)
        val result = try {
            require(requestId.matches(Regex("[a-f0-9-]{36}")) && capability.length <= 96) { "invalid_request" }
            require(deadline > System.currentTimeMillis()) { "deadline_expired" }
            wake.acquire((deadline - System.currentTimeMillis()).coerceIn(1_000, 120_000))
            if (capability == "device.describe") describe()
            else {
                require(capability.startsWith("android.")) { "unsupported_capability" }
                val action = capability.removePrefix("android.")
                val params = message.optJSONObject("params") ?: JSONObject()
                val scopeDirectory = File(filesDir, "remote-device/workspace").apply { mkdirs() }
                val raw = withContext(Dispatchers.IO) {
                    tool.execute(JSONObject().put("action", action).put("params", params), scopeDirectory, "remote-device")
                }
                val parsed = JSONObject(raw)
                if (action == "launch_app" && parsed.optJSONObject("data")?.optBoolean("launch_requested") == true) {
                    val target = params.optString("package_name")
                    val verified = hasUsageAccess() && observeForeground(target)
                    parsed.put("status", if (verified) "ok" else "unconfirmed")
                        .getJSONObject("data").put("foreground_verified", verified)
                        .put("verification_note", if (verified) "The requested application was observed in the foreground." else "Launch was requested, but foreground arrival was not confirmed.")
                }
                if (action == "root_request" && parsed.optString("status") == "ok") {
                    announce(webSocket)
                    syncDozeExemption()
                }
                parsed
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            JSONObject().put("status", "error").put("error", error.message?.take(120) ?: "execution_failed")
        } finally {
            if (wake.isHeld) wake.release()
        }
        runCatching { webSocket.send(JSONObject().put("type", "result").put("protocol", "capri.device/1")
            .put("request_id", requestId).put("result", result).toString()) }
    }

    private suspend fun observeForeground(target: String): Boolean {
        repeat(10) {
            val visible = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val events = getSystemService(UsageStatsManager::class.java).queryEvents(now - 2_000, now) ?: return@withContext false
                val event = UsageEvents.Event()
                var foreground: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> foreground = event.packageName
                        UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                            if (foreground == event.packageName) foreground = null
                    }
                }
                foreground == target
            }
            if (visible) return true
            delay(500)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean = getSystemService(AppOpsManager::class.java)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED

    private suspend fun syncDozeExemption() {
        val config = store.remoteDeviceConfig() ?: return
        if (config.optBoolean("doze_added_by_capri") || tool.rootStatus().optString("app_authorization") != "granted") return
        val workspace = File(filesDir, "remote-device/workspace").apply { mkdirs() }
        val query = tool.execute(JSONObject().put("action", "root_exec").put("params", JSONObject()
            .put("command", "cmd deviceidle whitelist").put("timeout_seconds", 10)), workspace, "remote-device")
        val data = JSONObject(query).optJSONObject("data") ?: return
        if (JSONObject(query).optString("status") != "ok") return
        val packagePattern = Regex("(?m)(?<![A-Za-z0-9_.])${Regex.escape(packageName)}(?![A-Za-z0-9_.])")
        if (packagePattern.containsMatchIn(data.optString("stdout"))) return
        val added = JSONObject(tool.execute(JSONObject().put("action", "root_exec").put("params", JSONObject()
            .put("command", "cmd deviceidle whitelist +$packageName").put("timeout_seconds", 10)), workspace, "remote-device"))
        if (added.optString("status") == "ok") store.saveRemoteDeviceConfig(config.put("doze_added_by_capri", true))
    }

    private suspend fun removeDozeExemption() {
        val config = store.remoteDeviceConfig() ?: return
        if (!config.optBoolean("doze_added_by_capri") || tool.rootStatus().optString("app_authorization") != "granted") return
        val workspace = File(filesDir, "remote-device/workspace").apply { mkdirs() }
        val removed = JSONObject(tool.execute(JSONObject().put("action", "root_exec").put("params", JSONObject()
            .put("command", "cmd deviceidle whitelist -$packageName").put("timeout_seconds", 10)), workspace, "remote-device"))
        if (removed.optString("status") == "ok") store.saveRemoteDeviceConfig(config.put("doze_added_by_capri", false))
    }

    private fun update(message: String) {
        if (!stopping.get()) getSystemService(NotificationManager::class.java).notify(NOTICE, notification(message))
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, DeviceNodeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(ai.capri.app.R.drawable.ic_capri)
            .setContentTitle("Capri · 设备接管已启用").setContentText(message).setContentIntent(open)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).addAction(0, "停止接管", stop).build()
    }

    override fun onDestroy() {
        stopping.set(true)
        socket?.close(1000, "service_stopped")
        socket = null
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        getSystemService(NotificationManager::class.java).cancel(NOTICE)
        super.onDestroy()
    }
}
