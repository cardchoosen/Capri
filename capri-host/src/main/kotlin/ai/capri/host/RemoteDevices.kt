package ai.capri.host

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/** Persistent paired-device identity plus the current authenticated live connection. */
internal class RemoteDevices(private val root: Path) {
    data class Device(
        val id: String,
        var name: String,
        var platform: String,
        var version: String,
        var capabilities: List<String>,
        val tokenDigest: ByteArray,
        var lastSeen: Long = 0,
        var connection: WebSocket? = null,
    )

    data class Pairing(val token: String, val expiresAt: Long, val socketUrl: String)
    private data class PendingPairing(val expiresAt: Long, val socketUrl: String)
    data class Invocation(val id: String, val deviceId: String, val capability: String,
                          val params: JSONObject, val future: CompletableFuture<JSONObject>)

    private val file = root.resolve("devices.json")
    private val random = SecureRandom()
    private val pairings = linkedMapOf<String, PendingPairing>()
    private val devices = linkedMapOf<String, Device>()
    private val invocations = linkedMapOf<String, Invocation>()

    init {
        Files.createDirectories(root)
        load()
    }

    @Synchronized fun createPairing(socketUrl: String): Pairing {
        prunePairings()
        val token = randomToken()
        val expiresAt = System.currentTimeMillis() + PAIR_TTL_MS
        pairings[hashKey(token)] = PendingPairing(expiresAt, socketUrl)
        return Pairing(token, expiresAt, socketUrl)
    }

    @Synchronized fun pair(token: String, name: String, platform: String, version: String,
                           capabilities: List<String>): JSONObject {
        val digest = hash(token)
        val pending = pairings[hashKey(token)]
        require(pending != null && pending.expiresAt >= System.currentTimeMillis()) { "pairing_expired" }
        require(name.isNotBlank() && name.length <= 80) { "invalid_device_name" }
        require(platform.matches(Regex("[a-z][a-z0-9_-]{1,31}"))) { "invalid_platform" }
        require(version.length <= 80 && capabilities.size <= 128 &&
            capabilities.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,95}")) }) { "invalid_capabilities" }
        pairings.remove(hashKey(token))
        val id = UUID.randomUUID().toString()
        val secret = randomToken()
        val device = Device(id, name.trim(), platform, version, capabilities.distinct().sorted(), hash(secret))
        devices[id] = device
        persist()
        return JSONObject().put("device_id", id).put("device_token", secret)
            .put("protocol", "capri.device/1").put("socket_url", pending.socketUrl)
    }

    @Synchronized fun authenticate(token: String): Device? {
        if (!token.matches(TOKEN_PATTERN)) return null
        val candidate = hash(token)
        return devices.values.firstOrNull { MessageDigest.isEqual(it.tokenDigest, candidate) }
    }

    @Synchronized fun attach(device: Device, socket: WebSocket): Boolean {
        val current = devices[device.id] ?: run { socket.close(1008, "device_revoked"); return false }
        if (!MessageDigest.isEqual(current.tokenDigest, device.tokenDigest)) {
            socket.close(1008, "device_revoked")
            return false
        }
        current.connection?.takeIf { it !== socket }?.close(1000, "replaced")
        current.connection = socket
        return true
    }

    @Synchronized fun connected(device: Device, socket: WebSocket, info: JSONObject) {
        val current = devices[device.id] ?: run { socket.close(1008, "device_revoked"); return }
        if (!MessageDigest.isEqual(current.tokenDigest, device.tokenDigest)) {
            socket.close(1008, "device_revoked")
            return
        }
        current.name = info.optString("name", current.name).take(80).ifBlank { current.name }
        current.platform = info.optString("platform", current.platform).take(32)
        current.version = info.optString("version", current.version).take(80)
        current.capabilities = info.optJSONArray("capabilities")?.let(::parseCapabilities) ?: current.capabilities
        current.lastSeen = System.currentTimeMillis()
        current.connection?.takeIf { it !== socket }?.close(1000, "replaced")
        current.connection = socket
        persist()
    }

    @Synchronized fun disconnected(deviceId: String, socket: WebSocket) {
        val current = devices[deviceId] ?: return
        if (current.connection === socket) {
            current.connection = null
            current.lastSeen = System.currentTimeMillis()
            persist()
        }
        invocations.values.filter { it.deviceId == deviceId }.forEach {
            it.future.completeExceptionally(IllegalStateException("device_disconnected"))
            invocations.remove(it.id)
        }
    }

    @Synchronized fun list(): JSONArray = JSONArray().apply {
        devices.values.sortedBy { it.name.lowercase() }.forEach { device ->
            put(JSONObject().put("id", device.id).put("name", device.name).put("platform", device.platform)
                .put("version", device.version).put("online", device.connection?.isOpen == true)
                .put("last_seen", device.lastSeen).put("capabilities", JSONArray(device.capabilities)))
        }
    }

    @Synchronized fun revoke(id: String): Boolean {
        val removed = devices.remove(id) ?: return false
        removed.connection?.close(1008, "device_revoked")
        invocations.values.filter { it.deviceId == id }.forEach {
            it.future.completeExceptionally(IllegalStateException("device_revoked"))
            invocations.remove(it.id)
        }
        persist()
        return true
    }

    fun invoke(deviceId: String, capability: String, params: JSONObject,
               timeoutMs: Long = DEFAULT_INVOKE_TIMEOUT_MS): JSONObject {
        val (socket, id, future) = synchronized(this) {
            val device = devices[deviceId] ?: throw WorkspaceFailure("device_unavailable", "The selected device is not paired.")
            val socket = device.connection?.takeIf(WebSocket::isOpen)
                ?: throw WorkspaceFailure("device_offline", "The selected device is offline.")
            if (capability !in device.capabilities) throw WorkspaceFailure("device_capability_unavailable", "The selected device does not advertise this capability.")
            val id = UUID.randomUUID().toString()
            val future = CompletableFuture<JSONObject>()
            invocations[id] = Invocation(id, deviceId, capability, JSONObject(params.toString()), future)
            Triple(socket, id, future)
        }
        try {
            socket.send(JSONObject().put("type", "invoke").put("protocol", "capri.device/1")
                .put("request_id", id).put("capability", capability).put("params", params)
                .put("deadline_ms", System.currentTimeMillis() + timeoutMs).toString())
        } catch (error: Exception) {
            synchronized(this) { invocations.remove(id) }
            throw WorkspaceFailure("device_disconnected", "The selected device connection ended before the operation was sent.")
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS).put("request_id", id).put("device_id", deviceId)
        } catch (_: java.util.concurrent.TimeoutException) {
            throw WorkspaceFailure("device_result_unknown", "The device did not confirm the operation before the deadline.")
        } catch (error: java.util.concurrent.ExecutionException) {
            throw WorkspaceFailure(error.cause?.message ?: "device_disconnected", "The device connection ended before a result was confirmed.")
        } finally {
            synchronized(this) { invocations.remove(id) }
        }
    }

    @Synchronized fun receive(device: Device, message: JSONObject) {
        if (message.optString("protocol") != "capri.device/1") return
        when (message.optString("type")) {
            "hello" -> connected(device, checkNotNull(device.connection), message)
            "result" -> {
                val id = message.optString("request_id")
                val invocation = invocations[id] ?: return
                if (invocation.deviceId != device.id) return
                val result = message.optJSONObject("result") ?: JSONObject().put("status", "error").put("error", "invalid_result")
                invocation.future.complete(JSONObject(result.toString()))
            }
            "pong" -> device.lastSeen = System.currentTimeMillis()
        }
    }

    private fun parseCapabilities(array: JSONArray): List<String> {
        require(array.length() <= 128) { "invalid_capabilities" }
        return (0 until array.length()).map(array::getString).also {
            require(it.all { value -> value.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,95}")) }) { "invalid_capabilities" }
        }.distinct().sorted()
    }

    private fun prunePairings() { pairings.entries.removeIf { it.value.expiresAt < System.currentTimeMillis() } }

    private fun load() {
        if (Files.notExists(file)) return
        val saved = JSONObject(Files.readString(file))
        val rows = saved.optJSONArray("devices") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val digest = Base64.getUrlDecoder().decode(row.getString("token_sha256"))
            if (digest.size != 32) continue
            val id = row.getString("id")
            if (!id.matches(UUID_PATTERN)) continue
            devices[id] = Device(id, row.optString("name", "Android device").take(80),
                row.optString("platform", "android").take(32), row.optString("version").take(80),
                parseCapabilities(row.optJSONArray("capabilities") ?: JSONArray()), digest, row.optLong("last_seen"))
        }
    }

    private fun persist() {
        val body = JSONObject().put("version", 1).put("devices", JSONArray().apply {
            devices.values.forEach { device -> put(JSONObject().put("id", device.id).put("name", device.name)
                .put("platform", device.platform).put("version", device.version)
                .put("capabilities", JSONArray(device.capabilities)).put("last_seen", device.lastSeen)
                .put("token_sha256", Base64.getUrlEncoder().withoutPadding().encodeToString(device.tokenDigest))) }
        }).toString()
        val temporary = Files.createTempFile(root, ".devices-", ".tmp")
        try {
            Files.writeString(temporary, body, StandardOpenOption.TRUNCATE_EXISTING)
            runCatching { Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    private fun hashKey(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(hash(value))
    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    companion object {
        private const val PAIR_TTL_MS = 10 * 60 * 1000L
        private const val DEFAULT_INVOKE_TIMEOUT_MS = 45_000L
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{40,64}")
        private val UUID_PATTERN = Regex("[a-f0-9-]{36}")
    }
}

/** Separate TLS WebSocket listener for outbound device-node sessions. */
internal class DeviceSocketServer(
    address: InetSocketAddress,
    private val tls: SSLContext,
    private val registry: RemoteDevices,
) : WebSocketServer(address, 8) {
    private val sessions = java.util.concurrent.ConcurrentHashMap<WebSocket, RemoteDevices.Device>()

    init { setWebSocketFactory(DefaultSSLWebSocketServerFactory(tls)) }

    override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
        if (handshake.resourceDescriptor.substringBefore('?') != "/api/devices/v1") {
            connection.close(1008, "invalid_endpoint")
            return
        }
        val header = handshake.getFieldValue("Authorization")
        val token = header.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: ""
        val device = registry.authenticate(token)
        if (device == null) {
            connection.close(1008, "device_authentication_failed")
            return
        }
        if (!registry.attach(device, connection)) return
        sessions[connection] = device
        connection.send(JSONObject().put("type", "welcome").put("protocol", "capri.device/1")
            .put("device_id", device.id).put("server_time", System.currentTimeMillis()).toString())
    }

    override fun onMessage(connection: WebSocket, message: String) {
        val device = sessions[connection] ?: run { connection.close(1008, "device_authentication_failed"); return }
        if (message.length > MAX_FRAME_CHARS) { connection.close(1009, "message_too_large"); return }
        val request = runCatching { JSONObject(message) }.getOrNull()
        if (request == null) { connection.close(1007, "invalid_json"); return }
        registry.receive(device, request)
    }

    override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
        sessions.remove(connection)?.let { registry.disconnected(it.id, connection) }
    }

    override fun onError(connection: WebSocket?, error: Exception) {
        if (connection != null) sessions.remove(connection)?.let { registry.disconnected(it.id, connection) }
    }

    override fun onStart() = Unit

    companion object { private const val MAX_FRAME_CHARS = 512 * 1024 }
}
