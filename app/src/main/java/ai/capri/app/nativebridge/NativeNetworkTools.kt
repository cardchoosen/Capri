package ai.capri.app.nativebridge

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.location.*
import android.net.*
import android.net.wifi.*
import android.os.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal class NativeNetworkTools(private val app: Application, private val permissions: NativePermissions) {
    private val connectivity get() = app.getSystemService(ConnectivityManager::class.java)
    private val wifi get() = app.getSystemService(WifiManager::class.java)
    private fun locationEnabled() = app.getSystemService(LocationManager::class.java).isLocationEnabled
    fun network(): JSONObject {
        val manager = connectivity
        val networks = manager.allNetworks.map { network ->
            val caps = manager.getNetworkCapabilities(network)
            val link = manager.getLinkProperties(network)
            JSONObject().put("active", network == manager.activeNetwork).put("transports", JSONArray(listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                NetworkCapabilities.TRANSPORT_VPN to "vpn", NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
                NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth"
            ).filter { caps?.hasTransport(it.first) == true }.map { it.second }))
                .put("internet_capability", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                .put("metered", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
                .put("interface", link?.interfaceName ?: JSONObject.NULL)
                .put("addresses", JSONArray(link?.linkAddresses?.map { it.toString() } ?: emptyList<String>()))
                .put("dns", JSONArray(link?.dnsServers?.map { it.hostAddress } ?: emptyList<String>()))
                .put("routes", JSONArray(link?.routes?.map { JSONObject().put("destination", it.destination.toString()).put("gateway", it.gateway?.hostAddress ?: JSONObject.NULL) } ?: emptyList<JSONObject>()))
        }
        return JSONObject().put("connected", manager.activeNetwork != null).put("networks", JSONArray(networks))
    }
    @Suppress("DEPRECATION")
    fun wifiInfo(): JSONObject {
        val manager = wifi ?: throw NativeFailure("unsupported", "设备不支持Wi-Fi")
        val info = manager.connectionInfo
        val fine = permissions.granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val enabled = locationEnabled()
        val ssid = info?.ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID || it == "<unknown ssid>" }?.removeSurrounding("\"")
        val bssid = info?.bssid?.takeUnless { it == "02:00:00:00:00:00" }
        val data = JSONObject().put("wifi_enabled", manager.isWifiEnabled).put("location_permission", fine).put("location_enabled", enabled)
            .put("ssid", ssid ?: JSONObject.NULL).put("bssid", bssid ?: JSONObject.NULL)
            .put("rssi_dbm", info?.rssi ?: JSONObject.NULL).put("frequency_mhz", info?.frequency ?: JSONObject.NULL)
            .put("link_speed_mbps", info?.linkSpeed ?: JSONObject.NULL).put("source", "Android WifiManager")
            .put("connected", connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true })
        if (!fine) data.put("next_action", "request_permissions").put("params", JSONObject().put("group", "location"))
        else if (!enabled) data.put("next_action", "open_settings").put("params", JSONObject().put("page", "location"))
        if (ssid == null) data.put("ssid_note", "当前未连接，或字段被系统隐藏；不能据此断言设备无能力")
        return result("wifi_info", if (!fine || !enabled || (manager.isWifiEnabled && ssid == null)) "partial" else "ok", data)
    }
    @Suppress("DEPRECATION")
    fun wifiScan(): JSONObject {
        permissions.requireGroup("location")
        if (!locationEnabled()) throw NativeFailure("setting_required", "读取Wi-Fi扫描结果需要开启系统位置开关", JSONObject().put("page", "location"))
        val manager = wifi ?: throw NativeFailure("unsupported", "设备不支持Wi-Fi")
        val now = SystemClock.elapsedRealtime()
        return JSONObject().put("source", "cached_scan_results").put("fresh_scan_requested", false).put("results", JSONArray(manager.scanResults.take(60).map {
            JSONObject().put("ssid", it.SSID).put("bssid", it.BSSID).put("frequency_mhz", it.frequency).put("rssi_dbm", it.level)
                .put("security", it.capabilities).put("age_ms", (now - it.timestamp / 1000).coerceAtLeast(0))
        }))
    }
    @Suppress("DEPRECATION")
    fun bluetooth(): JSONObject {
        permissions.requireGroup("bluetooth")
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: throw NativeFailure("unsupported", "设备不支持蓝牙")
        return JSONObject().put("enabled", adapter.isEnabled).put("name", adapter.name ?: JSONObject.NULL)
            .put("paired_devices", JSONArray(adapter.bondedDevices.take(50).map { JSONObject().put("name", it.name ?: JSONObject.NULL).put("address", it.address).put("type", it.type) }))
    }
    @Suppress("DEPRECATION")
    suspend fun location(params: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        if (!permissions.granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !permissions.granted(Manifest.permission.ACCESS_FINE_LOCATION)) permissions.requireGroup("location")
        val manager = app.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) throw NativeFailure("setting_required", "系统位置开关未开启", JSONObject().put("page", "location"))
        val precise = permissions.granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val candidates = if (precise) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER)
        val provider = candidates.firstOrNull { it in manager.allProviders && manager.isProviderEnabled(it) }
            ?: throw NativeFailure("unavailable", "没有可用的位置提供者")
        val future = CompletableDeferred<Location?>()
        val cancellation = CancellationSignal()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { future.complete(location) }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) { future.complete(null) }
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) manager.getCurrentLocation(provider, cancellation, app.mainExecutor) { future.complete(it) }
            else manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            val location = withTimeoutOrNull(params.boundedInt("timeout_ms", 15_000, 1000, 20_000).toLong()) { future.await() }
                ?: throw NativeFailure("unavailable", "在等待时间内没有获得定位；可能需要室外信号或网络定位服务")
            JSONObject().put("latitude", location.latitude).put("longitude", location.longitude).put("accuracy_m", location.accuracy.toDouble())
                .put("provider", location.provider).put("time_ms", location.time).put("age_ms", (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000)
                .put("precise_permission", permissions.granted(Manifest.permission.ACCESS_FINE_LOCATION))
        } finally { cancellation.cancel(); manager.removeUpdates(listener) }
    }
}
