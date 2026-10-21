package ai.capri.app.nativebridge

import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class NativeAppTools(private val app: Application, private val permissions: NativePermissions) {
    @Suppress("DEPRECATION")
    fun apps(params: JSONObject): JSONObject {
        val manager = app.packageManager
        val query = params.optString("query", "").trim()
        val apps = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }.map { it.activityInfo.packageName to it.loadLabel(manager).toString() }
            .filter { query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true) }.sortedBy { it.second }
        return JSONObject().put("scope", "launcher_apps_visible_to_Capri").put("count", apps.size)
            .put("apps", JSONArray(apps.take(params.boundedInt("limit", 50, 1, 100)).map { JSONObject().put("package_name", it.first).put("name", it.second) }))
    }
    @Suppress("DEPRECATION")
    fun info(params: JSONObject): JSONObject {
        val name = params.requiredText("package_name", 200)
        val manager = app.packageManager
        val info = try { manager.getPackageInfo(name, 0) } catch (_: PackageManager.NameNotFoundException) { throw NativeFailure("unavailable", "包未安装或不在Android应用可见范围内") }
        return JSONObject().put("package_name", name).put("name", info.applicationInfo?.loadLabel(manager)?.toString())
            .put("version", info.versionName).put("version_code", info.longVersionCode).put("first_install_ms", info.firstInstallTime)
            .put("updated_ms", info.lastUpdateTime).put("launchable", manager.getLaunchIntentForPackage(name) != null)
    }
    private suspend fun launch(intent: Intent, description: String): JSONObject = withContext(Dispatchers.Main) {
        if (!permissions.foreground()) throw NativeFailure("foreground_required", "请把Capri置于前台后打开系统或应用页面")
        try { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: ActivityNotFoundException) { throw NativeFailure("unsupported", "手机没有能够处理该操作的应用") }
        JSONObject().put("launch_requested", true).put("description", description).put("completed", false)
            .put("note", "仅已提交打开页面的请求；未确认用户在目标页面完成操作，不要报告为已保存或已发送")
    }
    suspend fun openApp(params: JSONObject): JSONObject {
        val name = params.requiredText("package_name", 200)
        val intent = app.packageManager.getLaunchIntentForPackage(name) ?: throw NativeFailure("unavailable", "该包不可见、未安装或没有启动页面")
        return launch(intent, "打开应用 $name")
    }
    suspend fun settings(params: JSONObject): JSONObject {
        val page = params.requiredText("page", 40)
        val pages = mapOf("wifi" to Settings.ACTION_WIFI_SETTINGS, "internet" to Settings.ACTION_WIRELESS_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS, "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "app" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "permissions" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS, "notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS, "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS, "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "all" to Settings.ACTION_SETTINGS)
        val action = pages[page] ?: throw NativeFailure("invalid_argument", "未知设置页面")
        val intent = Intent(action)
        if (page == "app" || page == "permissions") intent.data = Uri.parse("package:${app.packageName}")
        if (page == "notifications") intent.putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
        return launch(intent, "打开设置：$page")
    }
    suspend fun url(params: JSONObject): JSONObject {
        val url = params.requiredText("url", 8000)
        val parsed = Uri.parse(url)
        if (parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) throw NativeFailure("invalid_argument", "仅支持有主机名的http/https链接")
        return launch(Intent(Intent.ACTION_VIEW, parsed).addCategory(Intent.CATEGORY_BROWSABLE), "打开网页")
    }
    suspend fun share(params: JSONObject): JSONObject {
        val text = params.requiredText("text", 20_000)
        val intent = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), params.optString("title", "分享"))
        return launch(intent, "打开文本分享面板")
    }
    suspend fun clipboard(params: JSONObject, write: Boolean): JSONObject = withContext(Dispatchers.Main) {
        if (!permissions.foreground()) throw NativeFailure("foreground_required", "Android仅允许当前前台应用访问剪贴板，请返回Capri")
        val manager = app.getSystemService(ClipboardManager::class.java)
        if (write) {
            val text = params.requiredText("text", 20_000)
            manager.setPrimaryClip(ClipData.newPlainText("Capri", text))
            JSONObject().put("written", true).put("characters", text.length)
        } else {
            val clip = manager.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
            JSONObject().put("text", text?.take(20_000) ?: JSONObject.NULL).put("has_text", text != null)
                .put("note", "仅读取文本，不主动解析剪贴板URI或读取文件")
        }
    }
    suspend fun calendarInsert(params: JSONObject): JSONObject {
        val title = params.requiredText("title", 300)
        if (!params.has("start_ms") || !params.has("end_ms")) throw NativeFailure("invalid_argument", "新建事件需要明确的start_ms和end_ms")
        val (start, end) = calendarRange(params)
        return launch(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end).putExtra(CalendarContract.Events.DESCRIPTION, params.optString("text", "").take(4000)), "打开新建日历事件页面")
    }
    suspend fun alarm(params: JSONObject): JSONObject {
        val hour = params.boundedInt("hour", -1, 0, 23)
        val minute = params.boundedInt("minute", -1, 0, 59)
        return launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, params.optString("title", "Capri").take(300)).putExtra(AlarmClock.EXTRA_SKIP_UI, false), "打开闹钟设置页面")
    }
    suspend fun timer(params: JSONObject): JSONObject {
        val seconds = params.boundedInt("duration_seconds", -1, 1, 86_400)
        return launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, params.optString("title", "Capri").take(300)).putExtra(AlarmClock.EXTRA_SKIP_UI, false), "打开倒计时设置页面")
    }
}

internal fun calendarRange(params: JSONObject): Pair<Long, Long> {
    fun value(key: String, fallback: Long): Long {
        if (!params.has(key)) return fallback
        val n = params.opt(key) as? Number ?: throw NativeFailure("invalid_argument", "$key 必须是毫秒时间戳")
        if (n.toDouble() != n.toLong().toDouble()) throw NativeFailure("invalid_argument", "$key 必须是整数")
        return n.toLong()
    }
    val start = value("start_ms", System.currentTimeMillis())
    val end = value("end_ms", start + 7 * 86_400_000L)
    if (start < 0 || end <= start || end - start > 31 * 86_400_000L) throw NativeFailure("invalid_argument", "时间范围需递增且不超过31天")
    return start to end
}
