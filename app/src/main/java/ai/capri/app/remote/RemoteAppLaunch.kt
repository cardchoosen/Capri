package ai.capri.app.remote

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

internal class RemoteAppLaunch(private val context: Context, private val client: RemoteChatClient) {
    suspend fun waitForLaunch(canLaunch: () -> Boolean): String {
        val device = UUID.randomUUID().toString()
        repeat(60) {
            if (!canLaunch()) return "接单已停止，请让 Capri 保持在前台。"
            val command = withContext(Dispatchers.IO) { client.device("poll", JSONObject().put("device", device)) }
            if (command.has("id")) {
                val target = command.getString("package_name")
                val startedAt = System.currentTimeMillis()
                val requested = canLaunch() && requestLaunch(target)
                val observed = requested && observeForeground(target, startedAt)
                withContext(Dispatchers.IO) {
                    client.device("result", JSONObject().put("device", device).put("id", command.getString("id"))
                        .put("launch_requested", requested).put("observed_package", if (observed) target else ""))
                }
                return when {
                    observed -> "已确认目标应用到达前台。"
                    requested -> "已请求启动，尚未确认前台状态。"
                    else -> "无法启动指定应用。"
                }
            }
            delay(1_000)
        }
        return "本次等待已结束。"
    }

    private fun requestLaunch(target: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(target) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private suspend fun observeForeground(target: String, start: Long): Boolean {
        repeat(12) {
            delay(500)
            val observed = withContext(Dispatchers.IO) {
                val usage = context.getSystemService(UsageStatsManager::class.java)
                val events = usage.queryEvents(start, System.currentTimeMillis()) ?: return@withContext false
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
            if (observed) return true
        }
        return false
    }

    companion object {
        @Suppress("DEPRECATION")
        fun hasUsageAccess(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
            .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
}
