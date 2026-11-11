package ai.capri.host

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors

private val executorIdentifier = Regex("[a-zA-Z0-9_-]{1,64}")
private const val executorProtocol = "capri.document-executor/1"
private const val executorRoute = "/api/executor/v1"

internal fun startDocumentExecutor(args: List<String>) {
    require(args.size <= 2) { "Usage: capri-host --executor [address] [port]" }
    val bind = InetSocketAddress(args.getOrElse(0) { "127.0.0.1" }, args.getOrElse(1) { "8770" }.toInt())
    val token = executorToken(System.getenv("CAPRI_EXECUTOR_TOKEN"))
    val root = Path.of(requireNotNull(System.getenv("CAPRI_EXECUTOR_ROOT"))).toAbsolutePath().normalize()
    require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "The executor root must be a directory." }
    val exports = JSONObject(requireNotNull(System.getenv("CAPRI_EXECUTOR_FILES")))
    require(exports.length() in 1..100)
    val workspace = WorkspaceTools(root) { _, _ -> error("The executor only serves documents.") }
    val files = exports.keys().asSequence().associateWith { id ->
        require(id.matches(executorIdentifier))
        exports.getString(id).also { workspace.delegationSource(it) }
    }
    val tls = RemoteAccess.fromEnvironment(token, executor = true)
    require(tls != null || bind.address?.isLoopbackAddress == true) { "Remote executor listeners require TLS." }
    System.setProperty("sun.net.httpserver.maxReqTime", "10")
    System.setProperty("sun.net.httpserver.maxRspTime", "15")
    val server = tls?.listen(bind) ?: HttpServer.create(bind, 8)
    val threads = Executors.newFixedThreadPool(4)
    server.executor = threads
    server.createContext(executorRoute) { exchange ->
        exchange.use {
            val bearer = exchange.requestHeaders["Authorization"]?.singleOrNull()
            val (status, body) = when {
                bearer == null || !MessageDigest.isEqual(bearer.toByteArray(), "Bearer $token".toByteArray()) ->
                    401 to JSONObject().put("error", "unauthorized")
                exchange.requestURI.path != executorRoute || exchange.requestMethod != "POST" ->
                    404 to JSONObject().put("error", "not_found")
                else -> {
                    val bytes = exchange.requestBody.readNBytes(4097)
                    val request = if (bytes.size <= 4096) runCatching {
                        JSONObject(bytes.toString(Charsets.UTF_8))
                    }.getOrNull() else null
                    if (request?.optString("protocol") != executorProtocol) {
                        400 to JSONObject().put("error", "invalid_protocol")
                    } else when (request.optString("action")) {
                        "list" -> 200 to JSONObject().put("documents", JSONArray(files.keys.sorted()))
                        "read" -> {
                            val id = request.optString("document")
                            val document = files[id]?.let { path -> runCatching { workspace.delegationSource(path) }.getOrNull() }
                            if (document == null) 404 to JSONObject().put("error", "document_unavailable")
                            else 200 to JSONObject().put("document", id).put("revision", document.getString("revision"))
                                .put("content", document.getString("content")).put("partial", !document.isNull("nextOffset"))
                        }
                        else -> 400 to JSONObject().put("error", "invalid_action")
                    }
                }
            }
            exchange.reply(status, "application/json", body.put("protocol", executorProtocol).toString())
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(0)
        threads.shutdownNow()
    })
    server.start()
    println("Capri document executor listening on port ${server.address.port}")
}

internal class DocumentExecutors(raw: JSONObject) {
    private data class Connection(val url: HttpUrl, val token: String)
    private val workers = raw.keys().asSequence().associateWith { name ->
        require(name.matches(executorIdentifier))
        val entry = raw.getJSONObject(name)
        Connection(integrationUrl(entry.getString("endpoint")),
            executorToken(System.getenv(entry.getString("tokenEnv"))))
    }

    fun names(): List<String> = workers.keys.sorted()

    suspend fun execute(args: JSONObject): JSONObject {
        val name = args.optString("executor")
        return when (args.getString("action")) {
            "list" -> if (name.isEmpty()) JSONObject().put("executors", JSONArray(names()))
            else request(name, JSONObject().put("action", "list")).put("executor", name)
            "read" -> read(name, args.optString("document"), args.optString("revision").ifBlank { null })
            else -> throw WorkspaceFailure("invalid_action", "Choose list or read.")
        }
    }

    suspend fun read(name: String, id: String, revision: String?): JSONObject {
        if (!id.matches(executorIdentifier)) throw WorkspaceFailure("invalid_document", "Provide a valid document ID.")
        val result = request(name, JSONObject().put("action", "read").put("document", id))
        val received = result.optString("revision")
        if (result.optString("document") != id || !received.matches(Regex("[a-f0-9]{64}")) || result.opt("content") !is String)
            throw WorkspaceFailure("executor_invalid_response", "The executor returned an invalid document.")
        if (revision != null && revision != received)
            throw WorkspaceFailure("revision_conflict", "The remote document revision changed.")
        return result.put("executor", name)
    }

    private suspend fun request(name: String, body: JSONObject): JSONObject {
        val worker = workers[name] ?: throw WorkspaceFailure("executor_not_configured", "Choose a configured executor.")
        val request = Request.Builder().url(worker.url).header("Authorization", "Bearer ${worker.token}")
            .post(body.put("protocol", executorProtocol).toString().toRequestBody("application/json".toMediaType())).build()
        try {
            return HostIntegrationHttp.execute(request) { response ->
                if (response.code == 404) throw WorkspaceFailure("document_unavailable", "The executor could not serve the document.")
                if (!response.isSuccessful) throw WorkspaceFailure("executor_request_failed", "The executor rejected the request.")
                val bytes = response.body?.byteStream()?.readNBytes(131_073)
                if (bytes == null || bytes.size > 131_072)
                    throw WorkspaceFailure("executor_invalid_response", "The executor response exceeded its size limit.")
                val result = JSONObject(bytes.toString(Charsets.UTF_8))
                if (result.optString("protocol") != executorProtocol)
                    throw WorkspaceFailure("executor_invalid_response", "The executor protocol did not match.")
                result
            }
        } catch (failure: WorkspaceFailure) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: java.io.IOException) {
            throw WorkspaceFailure("executor_offline", "The executor could not be reached.")
        } catch (_: org.json.JSONException) {
            throw WorkspaceFailure("executor_invalid_response", "The executor response was not valid JSON.")
        }
    }
}

private fun executorToken(value: String?): String {
    require(value != null && value.matches(Regex("[a-zA-Z0-9_-]{32,256}"))) { "Configure a random executor bearer token." }
    return value
}
