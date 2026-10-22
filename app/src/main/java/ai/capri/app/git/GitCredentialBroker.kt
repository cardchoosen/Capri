package ai.capri.app.git

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Private IPC, not a TCP endpoint. Same-UID code is trusted, as elsewhere in this runtime. */
internal object GitCredentialBroker {
    private var server: LocalServerSocket? = null
    private val permits = Semaphore(4)
    private val workers = Executors.newFixedThreadPool(4) { task -> Thread(task, "CapriGitCredential").apply { isDaemon = true } }
    @Synchronized fun start(context: Context): String {
        val name = "${context.packageName}.git.${Process.myUid()}"
        if (server != null) return name
        val accounts = GitAccounts(context.applicationContext)
        val listener = LocalServerSocket(name)
        server = listener
        Thread({
            while (true) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                if (!permits.tryAcquire()) { socket.close(); continue }
                workers.execute {
                    try { handle(socket, accounts) } finally { runCatching { socket.close() }; permits.release() }
                }
            }
        }, "CapriGitBroker").apply { isDaemon = true; start() }
        return name
    }
    private fun handle(socket: LocalSocket, accounts: GitAccounts) {
        try {
            socket.soTimeout = 3000
            if (socket.peerCredentials.uid != Process.myUid()) return
            val input = socket.inputStream
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0 || b == 10) break
                check(bytes.size() < 16384)
                bytes.write(b)
            }
            val request = JSONObject(bytes.toString("UTF-8"))
            val host = GitCredentialPolicy.requestedHost(request.optString("protocol"), request.optString("host"))
            val secret = host?.let { accounts.secret(it, request.optString("username")) }
            val response = JSONObject()
            if (secret != null) response.put("username", secret.username).put("password", secret.token)
            val encoded = (response.toString() + "\n").toByteArray()
            check(encoded.size <= 32768)
            socket.outputStream.write(encoded)
        } catch (_: Exception) {
            // Do not log request, decrypted values, or exception messages.
            runCatching { socket.outputStream.write("{}\n".toByteArray()) }
        }
    }
}
