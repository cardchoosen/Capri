package ai.capri.host

import com.sun.net.httpserver.HttpExchange
import org.json.JSONObject
import java.util.UUID

/** One in-memory launch request for the explicitly armed LAN test device. */
internal class DeviceLaunch {
    private data class Launch(
        val id: String, val device: String, val target: String, val expires: Long,
        var state: String = "queued", var observed: String = "",
    )

    private var device = ""
    private var lastPoll = 0L
    private var launch: Launch? = null
    private val clock get() = System.nanoTime() / 1_000_000

    fun handle(exchange: HttpExchange) {
        val result = if (exchange.requestMethod == "GET") {
            if (exchange.requestURI.path == "/api/device/launch") 200 to status()
            else 405 to error("method_not_allowed")
        } else if (exchange.requestMethod == "POST") {
            val bytes = exchange.requestBody.readNBytes(2049)
            val body = if (bytes.size <= 2048) runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() else null
            if (body == null) 400 to error("invalid_request")
            else update(exchange.requestURI.path, body)
        } else 405 to error("method_not_allowed")
        exchange.reply(result.first, "application/json", result.second.toString())
    }

    @Synchronized private fun status(): JSONObject {
        expire()
        return JSONObject().put("online", device.isNotEmpty() && clock - lastPoll < 5_000).apply {
            launch?.let { put("launch", JSONObject().put("id", it.id).put("package_name", it.target)
                .put("state", it.state).put("observed_package", it.observed)) }
        }
    }

    @Synchronized private fun update(path: String, body: JSONObject): Pair<Int, JSONObject> {
        expire()
        val current = launch
        val caller = body.optString("device")
        return when (path) {
            "/api/device/poll" -> {
                if (!id.matches(caller)) return 400 to error("invalid_device")
                if (device != caller && (clock - lastPoll < 5_000 && device.isNotEmpty() ||
                        current?.state in setOf("queued", "dispatched"))) return 409 to error("device_busy")
                device = caller
                lastPoll = clock
                if (current?.device == caller && current.state == "queued") {
                    current.state = "dispatched"
                    200 to JSONObject().put("id", current.id).put("package_name", current.target)
                } else 200 to JSONObject()
            }
            "/api/device/launch" -> {
                val target = body.optString("package_name")
                if (!packageName.matches(target)) return 400 to error("invalid_package")
                if (device.isEmpty() || clock - lastPoll >= 5_000) return 409 to error("device_offline")
                if (current?.state in setOf("queued", "dispatched")) return 409 to error("device_busy")
                launch = Launch(UUID.randomUUID().toString(), device, target, clock + 20_000)
                202 to status()
            }
            "/api/device/result" -> {
                if (current == null || current.device != caller || current.id != body.optString("id") ||
                    current.state != "dispatched") return 409 to error("launch_unavailable")
                val observed = body.optString("observed_package")
                if (observed.isNotEmpty() && !packageName.matches(observed)) return 400 to error("invalid_package")
                current.observed = observed
                current.state = when {
                    observed == current.target -> "foreground_confirmed"
                    body.optBoolean("launch_requested") -> "foreground_unconfirmed"
                    else -> "launch_failed"
                }
                device = ""
                200 to status()
            }
            else -> 404 to error("not_found")
        }
    }

    private fun expire() {
        launch?.takeIf { it.state in setOf("queued", "dispatched") && clock >= it.expires }?.let {
            it.state = if (it.state == "queued") "expired" else "foreground_unconfirmed"
        }
    }

    private fun error(code: String) = JSONObject().put("error", code)

    companion object {
        private val id = Regex("[a-f0-9-]{36}")
        private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){1,20}")
    }
}
