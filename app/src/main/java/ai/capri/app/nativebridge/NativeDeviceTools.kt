package ai.capri.app.nativebridge

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import ai.capri.app.R
import android.content.*
import android.hardware.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal class NativeDeviceTools(private val app: Application, private val permissions: NativePermissions) {
    fun device() = JSONObject().put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND).put("model", Build.MODEL)
        .put("android_version", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT).put("security_patch", Build.VERSION.SECURITY_PATCH)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("locale", Locale.getDefault().toLanguageTag())
        .put("timezone", java.util.TimeZone.getDefault().id).put("time_ms", System.currentTimeMillis())
        .put("uptime_ms", SystemClock.elapsedRealtime()).put("app_package", app.packageName).put("app_uid", android.os.Process.myUid())
        .put("root_backend", false).put("adb_backend", false).put("shizuku_backend", false)
    fun battery(): JSONObject {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val manager = app.getSystemService(BatteryManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        return JSONObject().put("percent", if (level >= 0 && scale > 0) 100.0 * level / scale else JSONObject.NULL)
            .put("charging", manager.isCharging).put("plugged", intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
            .put("temperature_celsius", intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0))
            .put("voltage_mv", intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)).put("power_save", power.isPowerSaveMode)
            .put("interactive", power.isInteractive)
    }
    fun storage(): JSONObject {
        val stat = StatFs(app.filesDir.path)
        val memory = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return JSONObject().put("scope", "应用所在数据卷；不是任意文件访问授权")
            .put("total_bytes", stat.totalBytes).put("available_bytes", stat.availableBytes)
            .put("ram_total_bytes", memory.totalMem).put("ram_available_bytes", memory.availMem).put("low_memory", memory.lowMemory)
    }
    fun display(): JSONObject {
        val metrics = app.resources.displayMetrics
        val config = app.resources.configuration
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        return JSONObject().put("width_px", metrics.widthPixels).put("height_px", metrics.heightPixels)
            .put("density", metrics.density.toDouble()).put("density_dpi", metrics.densityDpi).put("font_scale", config.fontScale.toDouble())
            .put("orientation", config.orientation).put("device_locked", keyguard.isDeviceLocked)
            .put("brightness_0_255", Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1))
            .put("brightness_mode", Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1))
    }
    fun sensors(): JSONObject {
        val list = app.getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL)
        return JSONObject().put("sensors", JSONArray(list.map {
            JSONObject().put("name", it.name).put("type", it.type).put("string_type", it.stringType).put("vendor", it.vendor).put("power_ma", it.power.toDouble())
        }))
    }
    suspend fun sensor(params: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        val name = params.requiredText("sensor_type", 40)
        val types = mapOf("accelerometer" to Sensor.TYPE_ACCELEROMETER, "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "light" to Sensor.TYPE_LIGHT, "proximity" to Sensor.TYPE_PROXIMITY, "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD,
            "pressure" to Sensor.TYPE_PRESSURE, "gravity" to Sensor.TYPE_GRAVITY)
        val type = types[name] ?: throw NativeFailure("invalid_argument", "不支持该传感器类型")
        val manager = app.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(type) ?: throw NativeFailure("unsupported", "设备没有 $name 传感器")
        val future = CompletableDeferred<JSONObject>()
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                future.complete(JSONObject().put("sensor", name).put("name", sensor.name).put("values", JSONArray(event.values.map { it.toDouble() })).put("accuracy", event.accuracy).put("elapsed_realtime_ns", event.timestamp))
            }
        }
        if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) throw NativeFailure("unavailable", "传感器注册失败")
        try { withTimeoutOrNull(params.boundedInt("timeout_ms", 3000, 1000, 10_000).toLong()) { future.await() } ?: throw NativeFailure("timeout", "没有收到传感器采样") }
        finally { manager.unregisterListener(listener) }
    }
    private val streams get() = mapOf("media" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING, "alarm" to AudioManager.STREAM_ALARM, "notification" to AudioManager.STREAM_NOTIFICATION)
    fun notificationInfo(): JSONObject {
        val manager = app.getSystemService(NotificationManager::class.java)
        return JSONObject().put("enabled", manager.areNotificationsEnabled()).put("scope", "Capri only")
            .put("channels", JSONArray(manager.notificationChannels.map { JSONObject().put("id", it.id).put("name", it.name.toString()).put("importance", it.importance) }))
    }
    fun notificationPost(params: JSONObject): JSONObject {
        permissions.requireGroup("notifications")
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) throw NativeFailure("setting_required", "Capri通知被系统关闭", JSONObject().put("page", "notifications"))
        val id = params.boundedInt("id", 1, 1, 9_999_999)
        val title = params.requiredText("title", 200)
        val text = params.requiredText("text", 4000)
        manager.createNotificationChannel(NotificationChannel("capri_tasks", "Capri任务", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel("capri_tasks").importance == NotificationManager.IMPORTANCE_NONE) throw NativeFailure("setting_required", "Capri任务通知通道被关闭", JSONObject().put("page", "notifications"))
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)!!
        val notification = Notification.Builder(app, "capri_tasks").setSmallIcon(R.drawable.ic_capri).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(app, id, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        manager.notify(id, notification)
        return JSONObject().put("id", id).put("submitted", true).put("note", "已提交系统通知；实际提醒方式受系统勿扰和通知设置影响")
    }
    fun notificationCancel(params: JSONObject): JSONObject {
        val id = params.boundedInt("id", -1, 1, 9_999_999)
        app.getSystemService(NotificationManager::class.java).cancel(id)
        return JSONObject().put("id", id).put("cancel_requested", true).put("scope", "Capri only")
    }
    fun audio(): JSONObject {
        val manager = app.getSystemService(AudioManager::class.java)
        return JSONObject().put("ringer_mode", manager.ringerMode).put("music_active", manager.isMusicActive).put("streams", JSONObject().apply {
            streams.forEach { (key, type) -> put(key, JSONObject().put("level", manager.getStreamVolume(type)).put("max", manager.getStreamMaxVolume(type)).put("min", manager.getStreamMinVolume(type)).put("muted", manager.isStreamMute(type))) }
        })
    }
    fun volume(params: JSONObject): JSONObject {
        val manager = app.getSystemService(AudioManager::class.java)
        val stream = params.requiredText("stream", 30)
        val type = streams[stream] ?: throw NativeFailure("invalid_argument", "stream应为media/ring/alarm/notification")
        val level = params.boundedInt("level", -1, manager.getStreamMinVolume(type), manager.getStreamMaxVolume(type))
        manager.setStreamVolume(type, level, AudioManager.FLAG_SHOW_UI)
        val actual = manager.getStreamVolume(type)
        if (actual != level) throw NativeFailure("restricted", "系统未应用请求的音量", JSONObject().put("requested", level).put("actual", actual))
        return JSONObject().put("stream", stream).put("level", actual)
    }
    @Suppress("DEPRECATION")
    fun vibrate(params: JSONObject): JSONObject {
        val manager = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator else app.getSystemService(Vibrator::class.java)
        if (!manager.hasVibrator()) throw NativeFailure("unsupported", "设备没有震动器")
        val ms = params.boundedInt("duration_ms", 100, 1, 2000)
        manager.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        return JSONObject().put("requested", true).put("duration_ms", ms)
    }
    fun torch(params: JSONObject): JSONObject {
        permissions.requireGroup("camera")
        val enabled = params.boolean("enabled")
        val manager = app.getSystemService(CameraManager::class.java)
        val camera = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: throw NativeFailure("unsupported", "设备没有可用闪光灯")
        manager.setTorchMode(camera, enabled)
        return JSONObject().put("requested", true).put("enabled", enabled).put("note", "已提交系统手电筒请求，可能被相机等应用覆盖")
    }
}
