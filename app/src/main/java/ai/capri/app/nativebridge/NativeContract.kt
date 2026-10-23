package ai.capri.app.nativebridge

import org.json.JSONArray
import org.json.JSONObject

internal class NativeFailure(val status: String, override val message: String, val details: JSONObject = JSONObject()) : Exception(message)

internal data class NativeAction(val name: String, val title: String, val parameters: String, val effect: String = "read")

/** Closed action catalog: native calls never resolve arbitrary classes, methods or shell commands. */
internal object NativeCatalog {
    val actions = listOf(
        NativeAction("capabilities", "原生能力清单与权限状态", "无"),
        NativeAction("permissions", "查看已授权权限", "无"),
        NativeAction("request_permissions", "请求与任务相关的系统授权", "group: location/camera/bluetooth/contacts/calendar/notifications", "permission"),
        NativeAction("device_info", "设备、系统、语言和运行信息", "无"),
        NativeAction("battery_info", "电量、充电与省电状态", "无"),
        NativeAction("storage_info", "应用存储空间与内存", "无"),
        NativeAction("display_info", "屏幕尺寸、密度、亮度与锁屏状态", "无"),
        NativeAction("network_check", "按需检测外网代表站点及任务目标可达性", "url:可选HTTPS目标；仅探测origin，不发凭据；并行最多6秒、同网络缓存30秒；不是全外网保证"),
        NativeAction("network_info", "网络类型、IP、路由、DNS与VPN状态", "无"),
        NativeAction("wifi_info", "当前Wi-Fi连接信息", "SSID/BSSID需要精确定位授权和位置开关；缺少时返回partial"),
        NativeAction("wifi_scan", "读取最近的Wi-Fi扫描结果", "需精确定位和位置开关；返回缓存结果和时间，不发起新扫描"),
        NativeAction("bluetooth_info", "蓝牙状态与已配对设备", "需bluetooth授权；不会扫描或配对"),
        NativeAction("location_current", "获取当前定位", "需location授权；timeout_ms:1000..20000，默认15000"),
        NativeAction("sensors_list", "列出设备传感器", "无"),
        NativeAction("sensor_read", "读取一次传感器数据", "sensor_type:accelerometer/gyroscope/light/proximity/magnetic_field/pressure/gravity；timeout_ms:1000..10000"),
        NativeAction("apps_list", "列出可启动应用", "query:可选名称关键词；limit:1..100。不是所有已安装包"),
        NativeAction("app_info", "查询可见应用信息", "package_name:包名"),
        NativeAction("launch_app", "打开指定应用", "package_name:包名；仅返回启动请求已提交", "launch"),
        NativeAction("open_settings", "打开系统设置页面", "page:wifi/internet/bluetooth/location/app/permissions/accessibility/notifications/battery/display/sound/storage/all", "launch"),
        NativeAction("open_url", "用浏览器打开链接", "url:http或https链接", "launch"),
        NativeAction("share_text", "打开系统文本分享面板", "text:内容；title:可选。用户选择目标并发送，不等于已发送", "launch"),
        NativeAction("clipboard_read", "读取剪贴板文本", "仅前台可用；可能弹出系统提示"),
        NativeAction("clipboard_write", "写入剪贴板", "text:内容", "write"),
        NativeAction("notifications_info", "查询Capri通知开关和通道", "仅本应用，不读取其他应用通知"),
        NativeAction("notification_post", "发送一条本机通知", "title:标题；text:正文；id:可选1..9999999，默认1；需notifications授权", "write"),
        NativeAction("notification_cancel", "撤销Capri自己的通知", "id:通知编号1..9999999", "write"),
        NativeAction("audio_info", "查询媒体、铃声、闹钟音量", "无"),
        NativeAction("audio_set_volume", "调整指定音量", "stream:media/ring/alarm/notification；level:0到对应最大值；不自动修改勿扰模式", "write"),
        NativeAction("vibrate", "短时震动", "duration_ms:1..2000，默认100", "write"),
        NativeAction("torch_set", "开关手电筒", "enabled:布尔；需camera授权", "write"),
        NativeAction("contacts_query", "按关键词查找联系人电话", "query:必填关键词；limit:1..30；需contacts授权"),
        NativeAction("calendar_events", "查询日历事件", "start_ms/end_ms:毫秒时间戳，默认未来7天，最多31天；limit:1..50；需calendar授权"),
        NativeAction("calendar_insert", "打开系统日历的新建事件页面", "title:标题；start_ms/end_ms:起止时间；text:可选描述；用户保存后才创建", "launch"),
        NativeAction("alarm_create", "打开系统闹钟设置", "hour:0..23；minute:0..59；title:可选；用户确认，非创建回执", "launch"),
        NativeAction("timer_create", "打开系统倒计时设置", "duration_seconds:1..86400；title:可选；用户确认，非启动回执", "launch")
    )
    fun find(name: String) = actions.firstOrNull { it.name == name }
    fun spec(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "android")
        .put("description", "手机原生Android能力统一入口，无root/ADB/Shizuku。网络任务不确定或失败时用network_check，可传目标url；network_info只说明系统联网，不证明外网站点可达。先用capabilities了解能力。Wi-Fi、设备状态、应用、定位等应优先使用此工具而非PRoot。缺少授权时按结果调用request_permissions并重试；不能把Python失败说成手机无法实现。params填写对应action的参数，未知参数不执行。launch类仅打开系统页面，不代表用户完成了操作。")
        .put("parameters", JSONObject().put("type", "object").put("required", JSONArray(listOf("action")))
            .put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(actions.map { it.name })))
                .put("params", JSONObject().put("type", "object").put("description", "action所需参数，可通过capabilities查询；例如wifi_info不需要参数，request_permissions为{group:location}，open_settings为{page:wifi}，launch_app为{package_name:包名}。")))))
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
