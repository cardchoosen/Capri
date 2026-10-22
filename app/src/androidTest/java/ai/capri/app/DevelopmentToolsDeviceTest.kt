package ai.capri.app

import ai.capri.app.terminal.TerminalManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.SocketException

@RunWith(AndroidJUnit4::class)
class DevelopmentToolsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = TerminalManager.get(context)
    private suspend fun invoke(chat: String, action: String, params: JSONObject = JSONObject()): JSONObject {
        val response = JSONObject(manager.execute(chat, JSONObject().put("action", action).put("params", params)))
        assertEquals(response.toString(), "ok", response.getString("status"))
        return response.getJSONObject("data")
    }
    private suspend fun run(chat: String, terminal: String, command: String): String = withTimeout(120_000) {
        val job = invoke(chat, "run", JSONObject().put("terminal_id", terminal).put("command", command)
            .put("timeout_seconds", 90).put("wait_ms", 1000))
        var offset = 0
        val output = StringBuilder()
        while (true) {
            val data = invoke(chat, "read", JSONObject().put("terminal_id", terminal).put("command_id", job.getString("command_id"))
                .put("offset", offset).put("wait_ms", 20000))
            output.append(data.getString("output")); offset = data.getInt("next_offset")
            if (data.getBoolean("complete")) {
                assertEquals(output.toString(), 0, data.getJSONObject("command").getInt("exit_code"))
                break
            }
        }
        output.toString()
    }
    private fun foreground() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    @Test fun gitProjectsAndHttpDownloadsWorkWithoutChangingUserEnvironment() = runBlocking<Unit> {
        foreground()
        val store = Store(context)
        val runtime = PythonRuntime(context, store.workspace)
        val first = "devtools-test-first"
        val second = "devtools-test-second"
        val marker = File(context.filesDir, "runtime/rootfs/capri-test-${java.util.UUID.randomUUID()}")
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val workspace = store.workspace.folder(first, "workspace")
        val serving = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine().split(' ')
                    var line: String?
                    do { line = reader.readLine() } while (!line.isNullOrEmpty())
                    val path = request[1].substringBefore('?')
                    val root = File(workspace, "source/.git").canonicalFile
                    val source = File(root, path.removePrefix("/repo.git/")).canonicalFile
                    val (status, headers, body) = when {
                        path == "/redirect" -> Triple("302 Found", "Location: /artifact\r\n", byteArrayOf())
                        path == "/artifact" -> Triple("200 OK", "", "capri-download-中文\n".toByteArray())
                        path.startsWith("/repo.git/") && source.path.startsWith(root.path + "/") && source.isFile ->
                            Triple("200 OK", "", source.readBytes())
                        else -> Triple("404 Not Found", "", "missing".toByteArray())
                    }
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 $status\r\nContent-Type: application/octet-stream\r\n${headers}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (request[0] != "HEAD") output.write(body)
                }
            } catch (_: SocketException) { if (!server.isClosed) throw RuntimeException("fixture socket failed") }
        }.apply { isDaemon = true; start() }
        try {
            marker.writeText("keep base")
            store.workspace.folder(first, "home").resolve("keep.txt").writeText("keep home")
            val terminal = invoke(first, "open", JSONObject().put("title", "Development tools test")).getString("terminal_id")
            val status = runtime.execute(first, "exec(open('/opt/capri/bootstrap/environment_probe.py').read())")
            assertTrue(status, status.contains("exit=0"))
            val diagnostic = JSONObject(status.substringAfter('\n'))
            assertTrue(diagnostic.getJSONObject("tools").getJSONObject("git").getBoolean("available"))
            assertTrue(diagnostic.getJSONObject("tools").getJSONObject("curl").getBoolean("available"))
            assertTrue(diagnostic.getBoolean("git_https_helper"))
            assertTrue(diagnostic.getBoolean("tools_ca_bundle"))
            val created = run(first, terminal, """
                git --version && curl --version &&
                git config --global user.name 'Capri Test' && git config --global user.email 'capri-test@example.invalid' &&
                git init source && cd source && printf 'original\n' > note.txt && git add note.txt && git commit -m initial &&
                git update-server-info && cd /workspace && echo repository-ready
            """.trimIndent())
            assertTrue(created, created.contains("repository-ready"))
            val port = server.localPort
            val cloned = run(first, terminal, """
                cd /workspace && git clone http://127.0.0.1:$port/repo.git cloned && cd cloned &&
                printf 'changed\n' > note.txt && git diff --exit-code --quiet; diff_code=${'$'}?;
                test "${'$'}diff_code" = 1 && git add note.txt && git commit -m changed &&
                test "${'$'}(git rev-list --count HEAD)" = 2 && git status --porcelain && echo clone-edit-commit-ok
            """.trimIndent())
            assertTrue(cloned, cloned.contains("clone-edit-commit-ok"))
            val downloaded = run(first, terminal, """
                cd /workspace && curl --fail --location --connect-timeout 5 --max-time 15 --output artifact.part http://127.0.0.1:$port/redirect &&
                mv artifact.part artifact.txt &&
                if curl --fail --max-time 10 --output missing.part http://127.0.0.1:$port/missing; then false; else
                  rm -f missing.part; test -s artifact.txt && echo http-error-detected;
                fi
            """.trimIndent())
            assertTrue(downloaded, downloaded.contains("http-error-detected"))
            assertEquals("capri-download-中文\n", File(workspace, "artifact.txt").readText())
            val other = invoke(second, "open", JSONObject().put("title", "Development tools isolation test")).getString("terminal_id")
            val isolated = run(second, other, "test ! -e /workspace/cloned && test ! -e /workspace/artifact.txt && test ! -e /root/.gitconfig && git --version && echo isolated")
            assertTrue(isolated, isolated.contains("isolated"))
            assertEquals("keep base", marker.readText())
            assertEquals("keep home", store.workspace.folder(first, "home").resolve("keep.txt").readText())
            val layer = DevelopmentTools.prepare(context)
            assertEquals(layer, DevelopmentTools.prepare(context))
        } finally {
            server.close(); serving.join(6000); marker.delete()
            for (chat in listOf(first, second)) {
                for (record in manager.list(chat)) {
                    manager.execute(chat, JSONObject().put("action", "close").put("params", JSONObject().put("terminal_id", record.getString("terminal_id"))))
                    manager.execute(chat, JSONObject().put("action", "delete").put("params", JSONObject().put("terminal_id", record.getString("terminal_id"))))
                }
                store.workspace.root(chat).deleteRecursively()
            }
        }
    }

    @Test fun publicHttpsGitAndCurlVerifyCertificates() = runBlocking<Unit> {
        foreground()
        val chat = "devtools-test-https"
        val runtime = PythonRuntime(context, Store(context).workspace)
        try {
            val id = invoke(chat, "open", JSONObject().put("title", "HTTPS development tools test")).getString("terminal_id")
            val result = run(chat, id, "git -c http.connectTimeout=15 -c http.lowSpeedLimit=1 -c http.lowSpeedTime=30 clone --depth 1 https://github.com/octocat/Hello-World.git public-clone && test -f public-clone/README && curl --fail --location --connect-timeout 15 --max-time 30 --output example.html https://example.com && grep 'Example Domain' example.html && echo https-verified")
            assertTrue(result, result.contains("https-verified"))
            assertTrue(runtime.execute(chat, "import pathlib;assert pathlib.Path('example.html').is_file();print('download-visible')").contains("download-visible"))
        } finally {
            for (record in manager.list(chat)) {
                manager.execute(chat, JSONObject().put("action", "close").put("params", JSONObject().put("terminal_id", record.getString("terminal_id"))))
                manager.execute(chat, JSONObject().put("action", "delete").put("params", JSONObject().put("terminal_id", record.getString("terminal_id"))))
            }
            Store(context).workspace.root(chat).deleteRecursively()
        }
    }
}
