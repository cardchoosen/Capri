package ai.capri.app

import ai.capri.app.git.*
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
import java.io.InputStream
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

/** Local TLS fixture uses a test-only certificate/private key, never a real account. */
@RunWith(AndroidJUnit4::class)
class GitAccountsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val manager get() = TerminalManager.get(context)
    private suspend fun invoke(chat: String, action: String, params: JSONObject): JSONObject {
        val r = JSONObject(manager.execute(chat, JSONObject().put("action", action).put("params", params)))
        check(r.optString("status") == "ok") { "Terminal request failed" }
        return r.getJSONObject("data")
    }
    private suspend fun run(chat: String, terminal: String, command: String, ok: Boolean = true): String = withTimeout(120_000) {
        val job = invoke(chat, "run", JSONObject().put("terminal_id", terminal).put("command", command).put("timeout_seconds", 90).put("wait_ms", 1000))
        var offset = 0; val text = StringBuilder()
        while (true) {
            val read = invoke(chat, "read", JSONObject().put("terminal_id", terminal).put("command_id", job.getString("command_id")).put("offset", offset).put("wait_ms", 20000))
            text.append(read.getString("output")); offset = read.getInt("next_offset")
            if (read.getBoolean("complete")) {
                val exit = read.getJSONObject("command").getInt("exit_code")
                if (ok) assertEquals(text.toString(), 0, exit) else assertTrue("Expected authentication failure", exit != 0)
                break
            }
        }
        text.toString()
    }
    private fun readLine(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == 10) break
            check(output.size() < 8192); if (b != 13) output.write(b)
        }
        return output.toString("UTF-8")
    }
    @Test fun globalEncryptedAccountsAuthenticateClonePullAndPushAcrossChats() = runBlocking<Unit> {
        instrumentation.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        val accounts = GitAccounts(context)
        val store = Store(context)
        val runtime = PythonRuntime(context, store.workspace)
        val first = "git-auth-test-first"; val second = "git-auth-test-second"
        val password = "capri-test-only".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply { instrumentation.context.assets.open("git-fixture.p12").use { load(it, password) } }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val tls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
        val listener = tls.serverSocketFactory.createServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val host = "127.0.0.1:${listener.localPort}"
        assertFalse("Fixture origin already exists", accounts.list().any { it.host == host })
        val expectedToken = java.util.concurrent.atomic.AtomicReference("capri-fixture-" + java.util.UUID.randomUUID())
        val fixtureFailure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val requests = AtomicInteger(); val authenticated = AtomicInteger()
        val server = Thread {
            while (!listener.isClosed) try {
                listener.accept().use { socket ->
                    socket.soTimeout = 15000
                    val input = socket.getInputStream()
                    val request = readLine(input).split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = readLine(input); if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    requests.incrementAndGet()
                    val allowed = headers["authorization"] == okhttp3.Credentials.basic("fixture-user", expectedToken.get())
                    val out = socket.getOutputStream()
                    if (!allowed) {
                        out.write("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"Capri test\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        authenticated.incrementAndGet()
                        val length = headers["content-length"]?.toInt() ?: 0
                        check(length in 0..2_000_000)
                        val body = ByteArray(length); var count = 0
                        while (count < length) { val n = input.read(body, count, length-count); check(n > 0); count += n }
                        val service = if (request[1].contains("git-receive-pack")) "git-receive-pack" else "git-upload-pack"
                        val advertise = request[0] == "GET"
                        val args = listOf("/usr/bin/git", service.removePrefix("git-"), "--stateless-rpc") +
                            (if (advertise) listOf("--advertise-refs") else emptyList()) + "/workspace/repos/private.git"
                        val process = runtime.startProcess(first, store.workspace.folder(first,"fixture-run"), args)
                        process.outputStream.use { it.write(body) }
                        val data = process.inputStream.readBytes()
                        check(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0)
                        val announcement = "# service=$service\n"
                        val prefix = if (advertise) ("%04x".format(announcement.toByteArray().size + 4) + announcement + "0000").toByteArray() else byteArrayOf()
                        val responseBody = prefix + data
                        val contentType = "application/x-$service-" + if (advertise) "advertisement" else "result"
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${responseBody.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(responseBody)
                    }
                }
            } catch (error: Exception) { if (!listener.isClosed) { fixtureFailure.compareAndSet(null, error); android.util.Log.e("CapriAuthFixture", "Local fixture failed", error) } }
        }.apply { isDaemon = true; start() }
        try {
            accounts.save(host, "fixture-user", expectedToken.get())
            val persisted = context.getSharedPreferences("git-accounts", android.content.Context.MODE_PRIVATE).getString(host, "")!!
            assertFalse("Token must not be persisted in plaintext", persisted.contains(expectedToken.get()))
            assertTrue(accounts.list().any { it.host == host && it.username == "fixture-user" })
            val a = invoke(first, "open", JSONObject().put("title", "Git auth self-test")).getString("terminal_id")
            val b = invoke(second, "open", JSONObject().put("title", "Git auth second chat")).getString("terminal_id")
            for (chat in listOf(first, second)) {
                store.workspace.folder(chat, "workspace").resolve("fixture-ca.pem").outputStream().use { output -> instrumentation.context.assets.open("git-fixture.crt").use { it.copyTo(output) } }
            }
            run(first, a, "git init --bare repos/private.git && git -C repos/private.git config http.receivepack true && git init seed && cd seed && git config user.name 'Capri Test' && git config user.email 'test@example.invalid' && printf 'initial\\n' > note.txt && git add note.txt && git commit -m initial && git push ../repos/private.git HEAD:main && git --git-dir=../repos/private.git symbolic-ref HEAD refs/heads/main")
            val url = "https://$host/private.git"
            val clone = run(first, a, "cd /workspace && GIT_SSL_CAINFO=/workspace/fixture-ca.pem git clone $url private && cd private && git config user.name 'Capri Test' && git config user.email 'test@example.invalid' && printf 'changed\\n' >> note.txt && git add note.txt && git commit -m changed && GIT_SSL_CAINFO=/workspace/fixture-ca.pem git push origin HEAD && git remote -v")
            assertFalse("Token must not appear in command output", clone.contains(expectedToken.get()))
            run(second, b, "GIT_SSL_CAINFO=/workspace/fixture-ca.pem git clone $url second-copy && test \"\$(git -C second-copy rev-list --count HEAD)\" = 2")
            assertTrue("Authenticated requests were expected", authenticated.get() >= 4)
            val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null); setCertificateEntry("fixture", keys.getCertificate(keys.aliases().nextElement()))
            }
            val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
            val clientTls = SSLContext.getInstance("TLS").apply { init(null, tm.trustManagers, null) }
            val client = okhttp3.OkHttpClient.Builder().sslSocketFactory(clientTls.socketFactory, tm.trustManagers[0] as X509TrustManager)
                .followRedirects(false).callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
            assertTrue(GitAccountTest.check(accounts, host, url, client).startsWith("仓库可读取"))
            accounts.save(host, "fixture-user", "fixture-invalid-token")
            assertTrue(GitAccountTest.check(accounts, host, url, client).startsWith("认证失败"))
            accounts.save(host, "fixture-user", expectedToken.get())
            accounts.save(host, "fixture-user", "")
            // Replacement applies to existing terminals. Git erase/store must not mutate the vault.
            val old = expectedToken.get(); expectedToken.set("capri-fixture-" + java.util.UUID.randomUUID())
            accounts.save(host, "fixture-user", expectedToken.get())
            run(first, a, "cd /workspace/private && GIT_SSL_CAINFO=/workspace/fixture-ca.pem git fetch origin")
            run(first, a, "printf 'protocol=https\\nhost=$host\\n\\n' | git credential reject")
            assertTrue("Git reject must not delete global user credentials", accounts.list().any { it.host == host })
            assertNull(accounts.secret(host, "wrong-user"))
            assertNull(GitCredentialPolicy.requestedHost("http", host))
            accounts.save(host, "fixture-user", "\"".repeat(8192))
            val bounded = runtime.execute(first, """
                import subprocess,sys
                request='protocol=https\nhost=$host\n\n'
                response=subprocess.run([sys.executable,'-I','/opt/capri/bootstrap/git_credential.py','get'],input=request,text=True,capture_output=True,timeout=10)
                assert response.returncode==0 and ('password='+chr(34)*8192) in response.stdout
                wrong=subprocess.run([sys.executable,'-I','/opt/capri/bootstrap/git_credential.py','get'],input='protocol=http\nhost=$host\n\n',text=True,capture_output=True,timeout=10)
                assert not wrong.stdout
                print('bounded-credential-protocol-ok')
            """.trimIndent())
            assertTrue(bounded, bounded.contains("bounded-credential-protocol-ok"))
            accounts.save(host, "fixture-user", expectedToken.get())
            accounts.remove(host)
            val failed = run(second, b, "cd /workspace/second-copy && GIT_SSL_CAINFO=/workspace/fixture-ca.pem git fetch origin", ok = false)
            assertFalse(failed.contains(old) || failed.contains(expectedToken.get()))
            for (chat in listOf(first, second)) {
                store.workspace.root(chat).walkTopDown().filter { it.isFile && it.length() < 2_000_000 }.forEach {
                    val text = it.readBytes().toString(Charsets.ISO_8859_1)
                    assertFalse("Credential material leaked into session files", text.contains(old) || text.contains(expectedToken.get()))
                }
            }
            assertTrue(requests.get() > authenticated.get())
        } finally {
            accounts.remove(host); listener.close(); server.join(16000)
            for (chat in listOf(first, second)) {
                for (record in manager.list(chat)) {
                    val params = JSONObject().put("terminal_id", record.getString("terminal_id"))
                    manager.execute(chat, JSONObject().put("action", "close").put("params", params))
                    manager.execute(chat, JSONObject().put("action", "delete").put("params", params))
                }
                store.workspace.root(chat).deleteRecursively()
            }
        }
    }
}
