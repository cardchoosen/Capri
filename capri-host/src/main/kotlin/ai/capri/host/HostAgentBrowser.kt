package ai.capri.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Invokes the upstream agent-browser CLI with a closed action set and argv-only calls. */
internal class HostAgentBrowser(private val executable: String, private val data: Path) {
    suspend fun execute(session: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val action = args.getString("action")
        val profile = data.resolve("agent-browser").resolve(session.filter { it.isLetterOrDigit() || it == '-' }.take(80).ifBlank { "default" })
        Files.createDirectories(profile)
        val command = mutableListOf(executable, "--profile", profile.toString())
        val selector = args.optString("selector").takeIf(String::isNotBlank)
        val text = args.optString("text")
        when (action) {
            "open" -> command.addAll(listOf("open", validateUrl(args.getString("url"))))
            "snapshot" -> command.add("snapshot")
            "click", "hover", "check", "uncheck" -> command.addAll(listOf(action, requireSelector(selector)))
            "fill", "type" -> {
                if (text.length > 4000 || text.contains('\u0000')) throw WorkspaceFailure("invalid_browser_text", "Browser input text must be at most 4000 characters without NUL bytes.")
                command.addAll(listOf(action, requireSelector(selector), text))
            }
            "press" -> command.addAll(listOf("press", args.getString("key").take(64)))
            "select" -> command.addAll(listOf("select", requireSelector(selector), args.optString("value").take(512)))
            "scroll" -> {
                val delta = args.optInt("delta", 600).coerceIn(-5000, 5000)
                command.addAll(listOf("scroll", if (delta < 0) "up" else "down", kotlin.math.abs(delta).toString()))
            }
            "wait" -> {
                if (selector != null) command.addAll(listOf("wait", selector))
                else command.addAll(listOf("wait", "--load", "networkidle"))
            }
            "text" -> command.addAll(listOf("get", "text", selector ?: "body"))
            "status" -> command.addAll(listOf("get", "url"))
            "back", "forward", "reload", "close" -> command.add(action)
            else -> throw WorkspaceFailure("invalid_agent_browser_action", "Choose a supported agent-browser action.")
        }
        val process = try {
            ProcessBuilder(command).directory(data.toFile()).apply {
                environment()["AGENT_BROWSER_SESSION"] = "capri-${session.filter { it.isLetterOrDigit() || it == '-' }.take(80)}"
                environment()["AGENT_BROWSER_JSON"] = "true"
                environment()["AGENT_BROWSER_MAX_OUTPUT"] = "20000"
                environment()["AGENT_BROWSER_DOWNLOAD_PATH"] = data.resolve("agent-browser/downloads").toString()
                environment().keys.filter { it.contains(Regex("(?i)(key|token|secret|password|credential)")) }
                    .forEach(environment()::remove)
            }.redirectErrorStream(true).start()
        } catch (_: java.io.IOException) {
            throw WorkspaceFailure("agent_browser_unavailable", "The agent-browser executable could not be started.")
        }
        val captured = java.io.ByteArrayOutputStream()
        val reader = Thread {
            process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                var retained = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val keep = minOf(count, 24_001 - retained)
                    if (keep > 0) { captured.write(buffer, 0, keep); retained += keep }
                }
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(1000)
            throw WorkspaceFailure("agent_browser_timeout", "The agent-browser action exceeded 45 seconds.")
        }
        reader.join(1000)
        val output = captured.toString(Charsets.UTF_8)
        if (process.exitValue() != 0) throw WorkspaceFailure("agent_browser_failed", output.take(1200).ifBlank { "The agent-browser action failed." })
        JSONObject().put("action", action).put("output", output.take(24_000)).put("truncated", output.length > 24_000)
    }

    private fun requireSelector(value: String?): String = value
        ?: throw WorkspaceFailure("missing_browser_selector", "Provide a selector for this agent-browser action.")

    private fun validateUrl(raw: String): String {
        val url = raw.trim().toHttpUrlOrNull()
            ?: throw WorkspaceFailure("invalid_browser_url", "Use a valid HTTPS or loopback HTTP URL.")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
            !(url.isHttps || url.scheme == "http" && url.host in setOf("127.0.0.1", "::1", "localhost")))
            throw WorkspaceFailure("invalid_browser_url", "Browser navigation is limited to HTTPS and loopback HTTP URLs.")
        return url.toString()
    }

    companion object {
        fun fromEnvironment(data: Path): HostAgentBrowser? {
            val configured = System.getenv("CAPRI_AGENT_BROWSER_BIN")?.takeIf(String::isNotBlank)
            if (configured != null) return configured.takeIf { Files.isExecutable(Path.of(it)) }?.let { HostAgentBrowser(it, data) }
            val found = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
                .asSequence().map { Path.of(it).resolve("agent-browser") }.firstOrNull(Files::isExecutable)
            return found?.let { HostAgentBrowser(it.toString(), data) }
        }
    }
}
