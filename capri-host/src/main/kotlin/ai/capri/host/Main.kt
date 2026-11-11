package ai.capri.host

import ai.capri.app.agent.AgentLoop
import ai.capri.app.model.ModelContextRejected
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--executor") {
        startDocumentExecutor(args.drop(1))
        return
    }
    require(args.size <= 3) { "Usage: capri-host [bind-address] [port] [local-https-port]" }
    val address = args.getOrElse(0) { "127.0.0.1" }
    val port = args.getOrElse(1) { "8765" }.toInt()
    val bind = InetSocketAddress(address, port)
    val remote = RemoteAccess.fromEnvironment()
    val localPort = args.getOrNull(2)?.toInt()
    require(localPort == null || remote != null && localPort in 1..65535 && localPort != port) {
        "A separate local HTTPS port requires remote TLS configuration and a distinct port."
    }
    val chat = if (remote != null || bind.address?.isLoopbackAddress == true) LocalChat.fromEnvironment() else null
    require(remote == null || chat != null) { "Configure the model before enabling remote chat." }
    System.setProperty("jdk.httpserver.maxConnections", "16")
    System.setProperty("sun.net.httpserver.maxReqTime", "10")
    System.setProperty("sun.net.httpserver.maxRspTime", "180")
    val server = remote?.listen(bind) ?: HttpServer.create(bind, 8)
    val localServer = localPort?.let { checkNotNull(remote).listen(InetSocketAddress("127.0.0.1", it)) }
    val workers = Executors.newFixedThreadPool(16)
    val reminders = chat?.let { host ->
        host.sessions.recoverReports()
        Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({ runCatching { runBlocking { host.tickJobs() } } }, 0, 5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
    val device = DeviceLaunch()
    fun configure(listener: HttpServer, access: RemoteAccess?) {
        listener.executor = workers
        listener.createContext("/") { exchange ->
            exchange.use {
                when {
                    exchange.requestURI.path == "/" && exchange.requestMethod == "GET" ->
                        exchange.reply(200, "text/html", if (chat == null) page else hostPage.replace("__CAPRI_REMOTE__", (access != null).toString()))
                    exchange.requestMethod == "GET" && exchange.requestURI.path in hostAssets -> {
                        val (type, content) = hostAssets.getValue(exchange.requestURI.path)
                        exchange.reply(200, type, content)
                    }
                    exchange.requestURI.path == "/api/check" && exchange.requestMethod == "POST" &&
                        exchange.requestHeaders.getFirst("X-Capri-Fixture") == "1" -> {
                        val result = runCatching { runBlocking { runFixture() } }
                        val body = result.getOrElse { JSONObject().put("error", "fixture_failed") }
                        exchange.reply(if (result.isSuccess) 200 else 500, "application/json", body.toString())
                    }
                    access != null && exchange.requestURI.path == "/api/access" && exchange.requestMethod == "POST" &&
                        exchange.requestHeaders.getFirst("X-Capri-Chat") == "1" -> {
                        if (access.authorized(exchange)) {
                            access.startBrowserSession(exchange)
                            exchange.reply(200, "application/json", """{"status":"connected"}""")
                        } else exchange.reply(401, "application/json", """{"error":"unauthorized"}""")
                    }
                    access != null && exchange.requestURI.path.startsWith("/api/device/") &&
                        exchange.requestHeaders.getFirst("X-Capri-Chat") == "1" -> {
                        if (access.authorized(exchange) || exchange.requestURI.path == "/api/device/launch" &&
                            access.browserAuthorized(exchange)) device.handle(exchange)
                        else exchange.reply(401, "application/json", """{"error":"unauthorized"}""")
                    }
                    chat != null && (exchange.requestURI.path in setOf("/api/chat", "/api/chat/status", "/api/chat/events", "/api/chat/reading", "/api/chat/cancel", "/api/chat/steer", "/api/chat/options", "/api/sessions", "/api/sessions/export",
                        "/api/workspace/upload", "/api/workspace/transcribe", "/api/workspace/files", "/api/workspace/file",
                        "/api/settings", "/api/settings/mcp", "/api/skills/catalog", "/api/artifacts") || exchange.requestURI.path.startsWith("/api/artifacts/")) &&
                        exchange.requestHeaders.getFirst("X-Capri-Chat") == "1" -> {
                        val authorized = if (access != null) access.authorized(exchange) || access.browserAuthorized(exchange)
                        else exchange.requestHeaders["Host"]?.singleOrNull() in loopbackHosts(listener.address.port)
                        when {
                            !authorized -> exchange.reply(if (access != null) 401 else 404, "application/json", """{"error":"unauthorized"}""")
                            exchange.requestURI.path == "/api/chat" -> exchange.handleChat(chat)
                            exchange.requestURI.path.startsWith("/api/chat/") -> exchange.handleTurnControl(chat)
                            exchange.requestURI.path == "/api/skills/catalog" -> exchange.handleSkillCatalog(chat)
                            exchange.requestURI.path.startsWith("/api/settings") -> exchange.handleIntegrationSettings(chat)
                            exchange.requestURI.path == "/api/workspace/transcribe" -> exchange.handleTranscription(chat)
                            exchange.requestURI.path.startsWith("/api/artifacts") || exchange.requestURI.path.startsWith("/api/workspace/") ->
                                exchange.handleWorkspace(chat.sessions)
                            else -> exchange.handleSessions(chat)
                        }
                    }
                    else -> exchange.reply(404, "application/json", """{"error":"not_found"}""")
                }
            }
        }
    }
    configure(server, remote)
    localServer?.let { configure(it, null) }
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(0)
        localServer?.stop(0)
        workers.shutdownNow()
        reminders?.shutdownNow()
        chat?.sessions?.close()
    })
    server.start()
    localServer?.start()
    val scheme = if (remote == null) "http" else "https"
    val host = if (':' in address) "[$address]" else address
    println("Capri host listening at $scheme://$host:${server.address.port}/")
    if (localPort != null) println("Capri local chat listening at https://localhost:$localPort/")
}

private fun loopbackHosts(port: Int) = setOf("127.0.0.1:$port", "localhost:$port", "[::1]:$port")

private val hostPage by lazy { webAsset("index.html") }
private val hostAssets by lazy {
    mapOf("/style.css" to ("text/css" to webAsset("style.css")),
        "/host.css" to ("text/css" to webAsset("host.css")),
        "/host.js" to ("text/javascript" to webAsset("host.js")),
        "/markdown.js" to ("text/javascript" to webAsset("markdown.js")))
}

private fun webAsset(name: String): String =
    checkNotNull(object {}.javaClass.getResourceAsStream("/web/$name")).bufferedReader(Charsets.UTF_8).use { it.readText() }

internal fun HttpExchange.reply(status: Int, type: String, text: String) {
    replyBytes(status, "$type; charset=utf-8", text.toByteArray(Charsets.UTF_8))
}

internal fun HttpExchange.replyBytes(status: Int, type: String, bytes: ByteArray) {
    responseHeaders.set("Content-Type", type)
    responseHeaders.set("Cache-Control", "no-store")
    responseHeaders.set("X-Content-Type-Options", "nosniff")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.write(bytes)
}

private suspend fun runFixture(): JSONObject =
    runTurn("Report whether the local fixture loop ran.", JSONArray()) {
        val message = JSONObject().put("role", "assistant").put("content", "The local fixture loop ran.")
        JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message)))
    }.put("mode", "fixture")

internal suspend fun runTurn(
    prompt: String,
    messages: JSONArray,
    tools: HostTools? = null,
    outputTokens: Int = 0,
    onPhase: (AgentLoop.Phase) -> Unit = {},
    emit: ((JSONObject) -> Unit)? = null,
    persist: ((JSONArray, JSONArray, Set<String>) -> Unit)? = null,
    recovered: Set<String> = emptySet(),
    priorReceipts: JSONArray = JSONArray(),
    pendingInput: () -> String? = { null },
    hasPendingInput: () -> Boolean = { false },
    inferReply: suspend (AgentLoop.Input) -> JSONObject,
): JSONObject {
    tools?.trackContext(messages)
    val phases = JSONArray()
    val receipts = priorReceipts
    var requests = 0
    var answer = ""
    val started = linkedSetOf<String>()
    val failures = mutableMapOf<String, Pair<String, Int>>()
    val safeReads = setOf("read", "ls", "grep", "project", "coding_reference", "coding_diagnostics",
        "document_extract", "web_search", "web_read", "session_artifacts", "conversation_history")
    fun recordCheckpoint() { persist?.invoke(messages, receipts, started) }
    val loop = AgentLoop(object : AgentLoop.Host {
        override val used get() = requests
        override val maximum = if (tools == null) 3 else null
        override val maxToolBatch = null
        override val request = prompt
        override fun phase(value: AgentLoop.Phase) {
            phases.put(value.name.lowercase())
            onPhase(value)
        }
        override fun waiting() = tools?.question != null
        override fun canExecute() = !waiting() && !hasPendingInput()
        override fun parallelTool(call: JSONObject) =
            call.getJSONObject("function").getString("name") in safeReads
        override suspend fun acceptInput(): Boolean {
            val input = pendingInput() ?: return false
            messages.put(JSONObject().put("role", "user").put("content", input))
            failures.clear()
            recordCheckpoint()
            return true
        }
        override suspend fun prepare() = AgentLoop.Input(messages, tools?.modelSpecifications() ?: JSONArray(), outputTokens)
        override suspend fun infer(input: AgentLoop.Input): JSONObject {
            requests++
            return inferReply(input).also { response ->
                val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                check(tools != null || (message.optJSONArray("tool_calls")?.length() ?: 0) == 0) { "Tool calls are unavailable in this turn." }
            }
        }
        override suspend fun rebudget(error: ModelContextRejected, input: AgentLoop.Input) = Unit
        override suspend fun observe(input: AgentLoop.Input, response: JSONObject) = Unit
        override fun append(message: JSONObject) { messages.put(message) }
        override suspend fun checkpoint() = recordCheckpoint()
        override fun decision(choice: JSONObject, count: Int, decision: String, corrections: Int) = Unit
        override fun preamble(content: String, tools: List<String>) = Unit
        override fun status(message: String) = Unit
        override suspend fun execute(call: JSONObject): String {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!canExecute()) return JSONObject().put("status", "not_executed")
                .put("notice", "New user input must be considered first.").toString()
            val key = toolCallKey(call)
            if (!parallelTool(call) && key in recovered) return JSONObject().put("status", "not_executed")
                .put("error", "recovery_duplicate").put("notice", "Verify the earlier effect instead of repeating this action.").toString()
            if ((failures[key]?.second ?: 0) >= 3) return JSONObject().put("status", "error")
                .put("error", "repeated_tool_error").put("notice", "Change the approach before using this tool again.").toString()
            started.add(call.getString("id"))
            recordCheckpoint()
            emit?.invoke(JSONObject().put("type", "tool_start").put("id", call.getString("id"))
                .put("tool", call.getJSONObject("function").getString("name")))
            val available = checkNotNull(tools)
            return if (parallelTool(call) && call.getJSONObject("function").getString("name") !in setOf("web_search", "web_read"))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { available.execute(call) }
            else available.execute(call)
        }
        override suspend fun record(call: JSONObject, result: String) {
            messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", result))
            val toolResult = JSONObject(result)
            val key = toolCallKey(call)
            if (toolResult.optString("status") == "error" && toolResult.optString("error") != "repeated_tool_error") {
                val prior = failures[key]
                failures[key] = result to if (prior?.first == result) prior.second + 1 else 1
            } else if (toolResult.optString("status") == "ok") failures.remove(key)
            val receipt = JSONObject().put("tool", call.getJSONObject("function").getString("name"))
                .put("id", call.getString("id"))
                .put("status", toolResult.getString("status"))
                .putOpt("error", toolResult.opt("error"))
            receipts.put(receipt)
            recordCheckpoint()
            emit?.invoke(JSONObject(receipt.toString()).put("type", "tool_end").put("id", call.getString("id"))
                .put("output", result.take(4000)))
        }
        override suspend fun deliver(draft: String) {
            if (hasPendingInput()) throw AgentLoop.Redirect()
            answer = draft
        }
    })
    return JSONObject().put("outcome", loop.run().name.lowercase())
        .put("reply", tools?.question ?: answer).put("phases", phases).put("tool_receipts", receipts)
}

private val page = """
    <!doctype html>
    <html lang="zh-CN">
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Capri 独立宿主验证</title>
    <h1>Capri 独立宿主验证</h1>
    <p>运行固定测试会话，查看 JVM 内核的实际执行阶段。</p>
    <button id="run">运行验证</button>
    <pre id="result" role="status"></pre>
    <script>
    const button = document.querySelector('#run');
    const result = document.querySelector('#result');
    button.onclick = async () => {
      button.disabled = true;
      try {
        const response = await fetch('/api/check', {
          method: 'POST',
          headers: {'X-Capri-Fixture': '1'}
        });
        result.textContent = JSON.stringify(await response.json(), null, 2);
      } catch (error) {
        result.textContent = error.message;
      } finally {
        button.disabled = false;
      }
    };
    </script>
    </html>
""".trimIndent()
