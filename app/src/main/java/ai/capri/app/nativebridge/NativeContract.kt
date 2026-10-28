package ai.capri.app.nativebridge

import org.json.JSONArray
import org.json.JSONObject

internal class NativeFailure(val status: String, override val message: String, val details: JSONObject = JSONObject()) : Exception(message)

internal data class NativeAction(val name: String, val title: String, val parameters: String, val effect: String = "read")

/** Closed catalog. Explicit root_exec and typed root_inspect verify su identity before host execution. */
internal object NativeCatalog {
    val actions = listOf(
        NativeAction("capabilities", "List native capabilities and permission state", "None."),
        NativeAction("permissions", "View granted permissions", "None."),
        NativeAction("request_permissions", "Request task-required permissions", "group: location/camera/bluetooth/contacts/calendar/notifications", "permission"),
        NativeAction("root_status", "Inspect Root evidence and Capri authorization", "None. Does not request authorization. Missing su or PRoot failures do not establish absence of host Root."),
        NativeAction("root_request", "Request and verify Capri Root authorization", "None. Requests through the device Root manager, waits up to 30 seconds, and performs read-only identity verification. Every actual privileged operation rechecks permission.", "permission"),
        NativeAction("root_inspect", "Batch read-only environment inspection with archive reuse", "queries:1..12 objects {id,type,value?,contains?,limit_bytes?,ttl_seconds?,validity?}; types:read_file/list_directory/readlink/command_exists/getprop/uname/kernel_log. value required except uname/kernel_log. Resolve tool paths with command_exists (value:bare name, e.g. sh); do not guess PRoot /bin paths. read_file/list_directory/readlink require absolute host paths under /sys/, /proc/, /system/, /system_ext/, /vendor/, /odm/, /product/ or /apex/. Optional contains is a literal filter; limit_bytes:100..1800 default1000; ttl_seconds defaults600 (dynamic5, kernel_log0), dynamic max30 otherwise86400; validity:boot(default)/stable/environment/volatile. refresh:boolean defaultfalse bypasses cache; timeout_seconds:1..40 default15. Only missing queries share one managed root execution; no arbitrary shell. See per-item status/cached/verified_at; empty samples do not prove unsupported hardware.", "root_read"),
        NativeAction("root_exec", "Execute a managed host root command", "command:Android shell script; timeout_seconds:1..120 default30; output_limit:1000..64000 bytes default16000. Request authorization only if not yet granted; every execution verifies UID. Runs on Android, not Ubuntu/PRoot. Detached background processes are unsupported.", "root_execute"),
        NativeAction("device_info", "Read device and system information", "None."),
        NativeAction("battery_info", "Read battery and power-saving state", "None."),
        NativeAction("storage_info", "Read application storage and memory", "None."),
        NativeAction("display_info", "Read display and lock-screen state", "None."),
        NativeAction("network_check", "Check target and sample-site reachability", "url: optional HTTPS target; probes its origin without credentials. Parallel checks take up to 6 seconds; same-network cache lasts 30 seconds. Does not establish universal Internet access."),
        NativeAction("network_info", "Read network, IP, routes, DNS and VPN state", "None."),
        NativeAction("wifi_info", "Read current Wi-Fi connection", "SSID/BSSID require precise location permission and enabled location services; otherwise returns partial data."),
        NativeAction("wifi_scan", "Read cached Wi-Fi scan results", "Requires precise location permission and enabled location services. Returns cached results with timestamps; does not initiate a new scan."),
        NativeAction("bluetooth_info", "Read Bluetooth state and paired devices", "Requires bluetooth permission; does not scan or pair."),
        NativeAction("location_current", "Get current location", "Requires location permission; timeout_ms:1000..20000, default15000."),
        NativeAction("sensors_list", "List device sensors", "None."),
        NativeAction("sensor_read", "Read a sensor sample", "sensor_type:accelerometer/gyroscope/light/proximity/magnetic_field/pressure/gravity; timeout_ms:1000..10000."),
        NativeAction("apps_list", "List launchable applications", "query: optional name filter; limit:1..100. Does not list every installed package."),
        NativeAction("app_info", "Read visible application information", "package_name: Android package identifier."),
        NativeAction("launch_app", "Launch an application", "package_name: Android package identifier. Returns only whether a launch was requested.", "launch"),
        NativeAction("open_settings", "Open system settings", "page:wifi/internet/bluetooth/location/app/permissions/accessibility/notifications/battery/display/sound/storage/all", "launch"),
        NativeAction("open_url", "Open a URL in the system browser", "url: HTTP or HTTPS URL.", "launch"),
        NativeAction("share_text", "Open the system text-sharing sheet", "text: content; title: optional. The user selects a destination and sends; opening the sheet is not proof of delivery.", "launch"),
        NativeAction("clipboard_read", "Read clipboard text", "Foreground only; Android may display a privacy notice."),
        NativeAction("clipboard_write", "Write clipboard text", "text: content.", "write"),
        NativeAction("notifications_info", "Read Capri notification settings", "This application only; does not read other applications' notifications."),
        NativeAction("notification_post", "Post a local notification", "title: title; text: body; id: optional1..9999999, default1. Requires notifications permission.", "write"),
        NativeAction("notification_cancel", "Cancel a Capri notification", "id: notification identifier1..9999999.", "write"),
        NativeAction("audio_info", "Read audio stream volumes", "None."),
        NativeAction("audio_set_volume", "Set an audio stream volume", "stream:media/ring/alarm/notification; level:0..the stream maximum. Does not automatically change Do Not Disturb.", "write"),
        NativeAction("vibrate", "Vibrate briefly", "duration_ms:1..2000, default100.", "write"),
        NativeAction("torch_set", "Set the flashlight", "enabled: boolean; requires camera permission.", "write"),
        NativeAction("contacts_query", "Find contact phone numbers", "query: required keyword; limit:1..30. Requires contacts permission."),
        NativeAction("calendar_events", "Read calendar events", "start_ms/end_ms: epoch milliseconds, default next7days, maximum31days; limit:1..50. Requires calendar permission."),
        NativeAction("calendar_insert", "Open the calendar event editor", "title: title; start_ms/end_ms: event times; text: optional description. The event exists only after the user saves it.", "launch"),
        NativeAction("alarm_create", "Open the alarm editor", "hour:0..23; minute:0..59; title: optional. Requires user confirmation; launch is not proof of alarm creation.", "launch"),
        NativeAction("timer_create", "Open the timer editor", "duration_seconds:1..86400; title: optional. Requires user confirmation; launch is not proof of timer start.", "launch")
    )
    fun find(name: String) = actions.firstOrNull { it.name == name }
    fun spec(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "android")
        .put("description", "Native Android capabilities including an optional managed su root backend; no ADB/Shizuku dependency. Prefer root_inspect for independent read-only host environment queries: it reuses valid archive evidence and batches missing checks. Use root_exec for task commands; ordinary PRoot tools retain the app UID. root_request requests and verifies app authorization; root_status only reads known state. Reuse sufficient context instead of routinely calling capabilities/root_status first. Prefer native actions for device, Wi-Fi, app and location data. Use network_check with an optional target url when network reachability is uncertain; network_info does not prove website access. Follow permission-required results with request_permissions when appropriate. params must match the action; unknown parameters are rejected. Launch actions only request a system UI, not completion of its workflow.")
        .put("parameters", JSONObject().put("type", "object").put("required", JSONArray(listOf("action")))
            .put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(actions.map { it.name })))
                .put("params", JSONObject().put("type", "object").put("description", "Action-specific arguments; inspect capabilities for details. wifi_info takes none; examples: request_permissions {group:location}, open_settings {page:wifi}, launch_app {package_name:application.id}.")))))
}

internal fun JSONObject.requiredText(name: String, max: Int = 4000): String {
    val value = opt(name)
    if (value !is String || value.isBlank() || value.length > max) throw NativeFailure("invalid_argument", "$name 必须是1..${max}字符的字符串")
    return value.trim()
}
internal fun JSONObject.boundedInt(name: String, default: Int, min: Int, max: Int): Int {
    val value = if (has(name)) opt(name) as? Number ?: throw NativeFailure("invalid_argument", "$name 必须为整数") else default
    val number = value.toLong()
    if (value.toDouble() != number.toDouble() || number !in min.toLong()..max.toLong()) throw NativeFailure("invalid_argument", "$name 超出范围 $min..$max")
    return number.toInt()
}
internal fun JSONObject.boolean(name: String): Boolean = opt(name) as? Boolean ?: throw NativeFailure("invalid_argument", "$name 必须为布尔值")
internal fun result(action: String, status: String = "ok", data: JSONObject = JSONObject()) = JSONObject().put("action", action).put("status", status).put("data", data)
