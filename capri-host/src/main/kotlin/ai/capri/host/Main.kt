package ai.capri.host

import ai.capri.app.agent.AgentLoop
import ai.capri.app.model.ModelContextRejected
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors

fun main(args: Array<String>) {
    require(args.size <= 2) { "Usage: capri-host [bind-address] [port]" }
    val address = args.getOrElse(0) { "127.0.0.1" }
    val port = args.getOrElse(1) { "8765" }.toInt()
    val bind = InetSocketAddress(address, port)
    val remote = RemoteAccess.fromEnvironment()
    val chat = if (remote != null || bind.address?.isLoopbackAddress == true) LocalChat.fromEnvironment() else null
    require(remote == null || chat != null) { "Configure the model before enabling remote chat." }
    System.setProperty("jdk.httpserver.maxConnections", "16")
    System.setProperty("sun.net.httpserver.maxReqTime", "10")
    System.setProperty("sun.net.httpserver.maxRspTime", "60")
    val server = remote?.listen(bind) ?: HttpServer.create(bind, 8)
    val workers = Executors.newFixedThreadPool(16)
    server.executor = workers
    val device = DeviceLaunch()
    server.createContext("/") { exchange ->
        exchange.use {
            when {
                exchange.requestURI.path == "/" && exchange.requestMethod == "GET" ->
                    exchange.reply(200, "text/html", if (chat == null) page else hostPage.replace("__CAPRI_REMOTE__", (remote != null).toString()))
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
                remote != null && exchange.requestURI.path == "/api/access" && exchange.requestMethod == "POST" &&
                    exchange.requestHeaders.getFirst("X-Capri-Chat") == "1" -> {
                    if (remote.authorized(exchange)) {
                        remote.startBrowserSession(exchange)
                        exchange.reply(200, "application/json", """{"status":"connected"}""")
                    } else exchange.reply(401, "application/json", """{"error":"unauthorized"}""")
                }
                remote != null && exchange.requestURI.path.startsWith("/api/device/") &&
                    exchange.requestHeaders.getFirst("X-Capri-Chat") == "1" -> {
                    if (remote.authorized(exchange) || exchange.requestURI.path == "/api/device/launch" &&
                        remote.browserAuthorized(exchange)) device.handle(exchange)
                    else exchange.reply(401, "application/json", """{"error":"unauthorized"}""")
                }
                chat != null && exchange.requestURI.path in setOf("/api/chat", "/api/sessions", "/api/sessions/export") &&
                    exchange.requestHeaders.getFirst("X-Capri-Chat") == "1" -> {
                    val authorized = if (remote != null) remote.authorized(exchange) || remote.browserAuthorized(exchange)
                    else exchange.requestHeaders.getFirst("Host") in loopbackHosts(server.address.port)
                    when {
                        !authorized -> exchange.reply(if (remote != null) 401 else 404, "application/json", """{"error":"unauthorized"}""")
                        exchange.requestURI.path == "/api/chat" -> exchange.handleChat(chat)
                        else -> exchange.handleSessions(chat.sessions)
                    }
                }
                else -> exchange.reply(404, "application/json", """{"error":"not_found"}""")
            }
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(0)
        workers.shutdownNow()
        chat?.sessions?.close()
    })
    server.start()
    val scheme = if (remote == null) "http" else "https"
    val host = if (':' in address) "[$address]" else address
    println("Capri host listening at $scheme://$host:${server.address.port}/")
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
    val bytes = text.toByteArray(Charsets.UTF_8)
    responseHeaders.set("Content-Type", "$type; charset=utf-8")
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
    inferReply: suspend (JSONArray) -> JSONObject,
): JSONObject {
    val phases = JSONArray()
    var requests = 0
    var answer = ""
    val loop = AgentLoop(object : AgentLoop.Host {
        override val used get() = requests
        override val maximum = 3
        override val request = prompt
        override fun phase(value: AgentLoop.Phase) { phases.put(value.name.lowercase()) }
        override suspend fun prepare() = AgentLoop.Input(messages, JSONArray(), 64)
        override suspend fun infer(input: AgentLoop.Input): JSONObject {
            requests++
            return inferReply(input.messages).also { response ->
                val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                check(message.optJSONArray("tool_calls")?.length() ?: 0 == 0) { "Tool calls are unavailable in chat." }
                check(message.optString("content").length <= 12_000) { "Model reply is too long." }
            }
        }
        override suspend fun rebudget(error: ModelContextRejected, input: AgentLoop.Input) = Unit
        override suspend fun observe(input: AgentLoop.Input, response: JSONObject) = Unit
        override fun append(message: JSONObject) { messages.put(message) }
        override suspend fun checkpoint() = Unit
        override fun decision(choice: JSONObject, count: Int, decision: String, corrections: Int) = Unit
        override fun preamble(content: String, tools: List<String>) = Unit
        override fun status(message: String) = Unit
        override suspend fun execute(call: JSONObject): String = error("Chat does not accept tool calls.")
        override suspend fun record(call: JSONObject, result: String) = Unit
        override suspend fun deliver(draft: String) { answer = draft }
    })
    return JSONObject().put("outcome", loop.run().name.lowercase())
        .put("reply", answer).put("phases", phases)
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
