package ai.capri.app.nativebridge

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID

class NativePermissions(private val app: Application) {
    data class Request(val id: String, val permissions: List<String>, var launched: Boolean = false)
    private val mutable = MutableStateFlow<Request?>(null)
    val pending = mutable.asStateFlow()
    private val mutex = Mutex()
    private var response: CompletableDeferred<Map<String, Boolean>>? = null
    private var activity = WeakReference<Activity>(null)
    fun attach(host: Activity) { activity = WeakReference(host) }
    fun detach(host: Activity) { if (activity.get() === host) activity.clear() }
    fun foreground() = activity.get()?.let { !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() } == true
    fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
    fun groups(): Map<String, List<String>> = mapOf(
        "location" to listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
        "camera" to listOf(Manifest.permission.CAMERA),
        "bluetooth" to if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList(),
        "contacts" to listOf(Manifest.permission.READ_CONTACTS),
        "calendar" to listOf(Manifest.permission.READ_CALENDAR),
        "notifications" to if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    )
    fun snapshot() = JSONObject().apply {
        groups().forEach { (name, permissions) -> put(name, JSONObject().put("granted", permissions.all(::granted)).put("permissions", JSONArray(permissions.map { JSONObject().put("name", it).put("granted", granted(it)) }))) }
    }
    fun requireGroup(group: String) {
        val permissions = groups()[group] ?: throw NativeFailure("invalid_argument", "未知权限组")
        val missing = permissions.filterNot(::granted)
        if (missing.isNotEmpty()) throw NativeFailure("permission_required", "需要 $group 授权", JSONObject().put("missing_permissions", JSONArray(missing)).put("next_action", "request_permissions").put("params", JSONObject().put("group", group)))
    }
    fun take(id: String): List<String>? {
        val request = mutable.value ?: return null
        if (request.id != id || request.launched) return null
        request.launched = true
        return request.permissions
    }
    fun complete(values: Map<String, Boolean>) {
        response?.complete(values)
        response = null
        mutable.value = null
    }
    suspend fun request(group: String): JSONObject = mutex.withLock {
        val permissions = groups()[group] ?: throw NativeFailure("invalid_argument", "未知权限组", JSONObject().put("groups", JSONArray(groups().keys)))
        if (permissions.all(::granted)) return@withLock JSONObject().put("group", group).put("granted", true)
        if (!foreground()) throw NativeFailure("foreground_required", "请将Capri置于前台后请求授权")
        if (mutable.value != null) throw NativeFailure("busy", "已有系统授权窗口等待处理")
        val request = Request(UUID.randomUUID().toString(), permissions)
        val future = CompletableDeferred<Map<String, Boolean>>()
        response = future
        mutable.value = request
        try {
            withTimeoutOrNull(60_000) { future.await() } ?: throw NativeFailure("timeout", "系统授权尚未完成，请完成手机上的授权窗口后重试")
            JSONObject().put("group", group).put("granted", permissions.all(::granted)).put("permissions", snapshot().getJSONObject(group).getJSONArray("permissions"))
        } finally {
            // A launched OS dialog owns the callback until it completes, even if its Agent task was cancelled.
            if (mutable.value?.id == request.id && !request.launched) { response = null; mutable.value = null }
        }
    }
}
