package ai.capri.app.nativebridge

import android.app.Application
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AndroidTool(private val app: Application) {
    val permissions = NativePermissions(app)
    private val device = NativeDeviceTools(app, permissions)
    private val network = NativeNetworkTools(app, permissions)
    private val apps = NativeAppTools(app, permissions)
    private val data = NativeDataTools(app, permissions)
    fun specification() = NativeCatalog.spec()
    fun capabilities(): JSONObject {
        val groups = permissions.groups()
        val permissionGroups = mapOf("wifi_scan" to "location", "location_current" to "location", "bluetooth_info" to "bluetooth", "torch_set" to "camera", "contacts_query" to "contacts", "calendar_events" to "calendar", "notification_post" to "notifications")
        val hardware = mapOf("wifi_info" to PackageManager.FEATURE_WIFI, "wifi_scan" to PackageManager.FEATURE_WIFI,
            "bluetooth_info" to PackageManager.FEATURE_BLUETOOTH, "torch_set" to PackageManager.FEATURE_CAMERA_FLASH)
        return JSONObject().put("backend", "Android native APIs").put("requires_root", false).put("requires_adb", false).put("requires_shizuku", false)
            .put("actions", JSONArray(NativeCatalog.actions.map { spec ->
                val group = permissionGroups[spec.name]
                val missing = groups[group].orEmpty().filterNot(permissions::granted)
                val supported = hardware[spec.name]?.let { app.packageManager.hasSystemFeature(it) } ?: true
                JSONObject().put("action", spec.name).put("title", spec.title).put("params", spec.parameters).put("effect", spec.effect)
                    .put("status", if (!supported) "unsupported" else if (missing.isNotEmpty()) "permission_required" else "available")
                    .put("missing_permissions", JSONArray(missing))
            })).put("permissions", permissions.snapshot())
            .put("not_implemented", JSONArray(listOf("accessibility UI automation", "screen capture", "camera capture", "microphone recording", "notification reading", "SMS", "arbitrary system settings writes")))
            .put("note", "available表示接口已接入；字段、前台状态、硬件、系统开关及厂商限制仍会影响执行，不能声称拥有全部Android权限。打开设置不会授予无障碍权限或自动化能力。")
    }
    suspend fun execute(arguments: JSONObject): String = withContext(Dispatchers.IO) {
        val action = arguments.optString("action", "")
        try {
            if (NativeCatalog.find(action) == null) throw NativeFailure("unsupported", "未知或未实现的原生action；请查询capabilities")
            if (arguments.has("params") && arguments.opt("params") !is JSONObject) throw NativeFailure("invalid_argument", "params必须是对象")
            val params = arguments.optJSONObject("params") ?: JSONObject()
            validateParams(action, params)
            val output = when (action) {
                "capabilities" -> capabilities()
                "permissions" -> permissions.snapshot()
                "request_permissions" -> permissions.request(params.requiredText("group", 30))
                "device_info" -> device.device()
                "battery_info" -> device.battery()
                "storage_info" -> device.storage()
                "display_info" -> device.display()
                "network_info" -> network.network()
                "wifi_info" -> return@withContext network.wifiInfo().toString()
                "wifi_scan" -> network.wifiScan()
                "bluetooth_info" -> network.bluetooth()
                "location_current" -> network.location(params)
                "sensors_list" -> device.sensors()
                "sensor_read" -> device.sensor(params)
                "notifications_info" -> device.notificationInfo()
                "notification_post" -> device.notificationPost(params)
                "notification_cancel" -> device.notificationCancel(params)
                "audio_info" -> device.audio()
                "audio_set_volume" -> device.volume(params)
                "vibrate" -> device.vibrate(params)
                "torch_set" -> device.torch(params)
                "apps_list" -> apps.apps(params)
                "app_info" -> apps.info(params)
                "launch_app" -> apps.openApp(params)
                "open_settings" -> apps.settings(params)
                "open_url" -> apps.url(params)
                "share_text" -> apps.share(params)
                "clipboard_read" -> apps.clipboard(params, false)
                "clipboard_write" -> apps.clipboard(params, true)
                "contacts_query" -> data.contacts(params)
                "calendar_events" -> data.calendar(params)
                "calendar_insert" -> apps.calendarInsert(params)
                "alarm_create" -> apps.alarm(params)
                "timer_create" -> apps.timer(params)
                else -> throw NativeFailure("unsupported", "未实现")
            }
            val status = if (action == "request_permissions" && !output.optBoolean("granted")) "permission_denied" else if (output.optBoolean("launch_requested")) "user_action_required" else "ok"
            result(action, status, output).toString()
        } catch (e: CancellationException) { throw e }
        catch (e: NativeFailure) { result(action, e.status, e.details.put("message", e.message)).toString() }
        catch (_: SecurityException) { result(action, "restricted", JSONObject().put("message", "Android拒绝此项操作；检查权限、系统策略或特殊访问授权。不能据此推断所有原生能力不可用。" )).toString() }
        catch (e: Exception) { result(action, "error", JSONObject().put("message", e.message?.take(300) ?: e.javaClass.simpleName)).toString() }
    }
}

internal fun validateParams(action: String, params: JSONObject) {
    val allowed = when (action) {
        "request_permissions" -> setOf("group")
        "location_current" -> setOf("timeout_ms")
        "sensor_read" -> setOf("sensor_type", "timeout_ms")
        "apps_list" -> setOf("query", "limit")
        "app_info", "launch_app" -> setOf("package_name")
        "open_settings" -> setOf("page")
        "open_url" -> setOf("url")
        "share_text" -> setOf("text", "title")
        "clipboard_write" -> setOf("text")
        "notification_post" -> setOf("title", "text", "id")
        "notification_cancel" -> setOf("id")
        "audio_set_volume" -> setOf("stream", "level")
        "vibrate" -> setOf("duration_ms")
        "torch_set" -> setOf("enabled")
        "contacts_query" -> setOf("query", "limit")
        "calendar_events" -> setOf("start_ms", "end_ms", "limit")
        "calendar_insert" -> setOf("title", "start_ms", "end_ms", "text")
        "alarm_create" -> setOf("hour", "minute", "title")
        "timer_create" -> setOf("duration_seconds", "title")
        else -> emptySet()
    }
    val unknown = params.keys().asSequence().filterNot { it in allowed }.toList()
    if (unknown.isNotEmpty()) throw NativeFailure("invalid_argument", "未知参数：${unknown.joinToString()}；可用参数：${allowed.joinToString()}")
}
