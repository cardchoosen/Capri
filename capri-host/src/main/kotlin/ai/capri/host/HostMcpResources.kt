package ai.capri.host

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Resource-only Streamable HTTP client. An operator selects the endpoint and exact readable URIs. */
internal class HostMcpResources(private val endpoint: HttpUrl, private val token: String?, private val readable: Set<String>) {
    private val lock = Mutex()
    private var sequence = 0L
    private var protocol: String? = null
    private var session: String? = null

    suspend fun settings(): JSONObject {
        val page = execute(JSONObject().put("action", "list"))
        val names = jsonObjects(page.getJSONArray("resources")).map { resource ->
            resource.optString("name").ifBlank { "MCP resource" }
        }
        return JSONObject().put("status", "connected").put("approvedCount", readable.size)
            .put("resources", JSONArray(names)).put("more", page.has("nextCursor"))
    }

    suspend fun execute(args: JSONObject): JSONObject = lock.withLock {
        val uri = args.optString("uri")
        val read = args.getString("action") == "read"
        if (read && uri !in readable) throw WorkspaceFailure("mcp_resource_not_allowed", "Use an operator-approved resource URI.")
        val method = if (read) "resources/read" else "resources/list"
        val params = if (read) JSONObject().put("uri", uri) else JSONObject().putOpt("cursor", args.opt("cursor"))
        if (protocol == null) connect()
        val result = try {
            request(method, params)
        } catch (_: McpSessionExpired) {
            protocol = null
            session = null
            connect()
            request(method, params)
        }
        if (!read) {
            val entries = jsonObjects(result.optJSONArray("resources") ?: JSONArray())
                .filter { it.optString("uri") in readable }.take(30).map { resource ->
                    JSONObject().put("uri", resource.getString("uri")).put("name", resource.optString("name").take(160))
                        .put("description", resource.optString("description").take(300))
                }
            return@withLock JSONObject().put("resources", JSONArray(entries)).putOpt("nextCursor", result.opt("nextCursor"))
        }
        val entries = jsonObjects(result.optJSONArray("contents") ?: JSONArray())
            .filter { it.optString("uri") == uri && it.opt("text") is String }.take(4).map { item ->
                val content = item.getString("text")
                JSONObject().put("text", content.take(4000)).put("truncated", content.length > 4000)
            }
        if (entries.isEmpty()) throw WorkspaceFailure("mcp_no_text", "The approved resource returned no text.")
        JSONObject().put("uri", uri).put("contents", JSONArray(entries))
    }

    private suspend fun connect() {
        protocol = null
        session = null
        val result = request("initialize", JSONObject().put("protocolVersion", "2025-11-25")
            .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "Capri").put("version", "1")))
        val version = result.optString("protocolVersion")
        if (version !in setOf("2025-03-26", "2025-06-18", "2025-11-25") ||
            result.optJSONObject("capabilities")?.has("resources") != true)
            throw WorkspaceFailure("mcp_unsupported", "The server did not negotiate a supported resource protocol.")
        protocol = version
        try {
            request("notifications/initialized", JSONObject(), notification = true)
        } catch (failure: Exception) {
            protocol = null
            session = null
            throw failure
        }
    }

    private suspend fun request(method: String, params: JSONObject, notification: Boolean = false): JSONObject {
        val id = ++sequence
        val payload = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        if (!notification) payload.put("id", id)
        val builder = Request.Builder().url(endpoint).header("Accept", "application/json, text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (token != null) builder.header("Authorization", "Bearer $token")
        protocol?.let { builder.header("MCP-Protocol-Version", it) }
        session?.let { builder.header("MCP-Session-Id", it) }
        return HostIntegrationHttp.execute(builder.build()) { response ->
            if (response.code == 404 && session != null) throw McpSessionExpired()
            if (!response.isSuccessful) throw WorkspaceFailure("mcp_http_error", "The MCP server returned HTTP ${response.code}.")
            if (notification) return@execute JSONObject()
            val body = checkNotNull(response.body)
            val packet = if (body.contentType()?.subtype == "event-stream") {
                val source = body.source()
                var remaining = 256L * 1024
                var matched: JSONObject? = null
                while (!source.exhausted() && matched == null) {
                    val line = source.readUtf8LineStrict(remaining)
                    remaining -= line.toByteArray(Charsets.UTF_8).size + 1
                    if (remaining <= 0) break
                    if (line.startsWith("data:")) {
                        val packet = JSONObject(line.removePrefix("data:").trimStart())
                        if (packet.optLong("id", -1) == id) matched = packet
                    }
                }
                matched ?: throw WorkspaceFailure("mcp_invalid_response", "The MCP stream returned no matching response.")
            } else {
                val bytes = body.byteStream().readNBytes(256 * 1024 + 1)
                if (bytes.size > 256 * 1024) throw WorkspaceFailure("mcp_response_too_large", "The MCP response exceeds 256 KiB.")
                JSONObject(bytes.toString(Charsets.UTF_8))
            }
            if (packet.optLong("id", -1) != id || packet.has("error"))
                throw WorkspaceFailure("mcp_rpc_error", "The MCP server did not complete the resource request.")
            if (method == "initialize") session = response.header("MCP-Session-Id")?.takeIf {
                it.length <= 256 && it.all { char -> char in '!'..'~' }
            }
            packet.optJSONObject("result") ?: throw WorkspaceFailure("mcp_invalid_response", "The MCP response has no result.")
        }
    }

    private class McpSessionExpired : java.io.IOException()
}
