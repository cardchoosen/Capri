package ai.capri.host

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

internal class RemoteAccess private constructor(
    private val tls: SSLContext,
    private val tokenDigest: ByteArray,
) {
    private val browserSession = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    fun listen(bind: InetSocketAddress): HttpsServer = HttpsServer.create(bind, 8).apply {
        httpsConfigurator = HttpsConfigurator(tls)
    }

    fun authorized(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders["Authorization"]?.singleOrNull() ?: return false
        if (!header.startsWith("Bearer ") || header.length > 263) return false
        return MessageDigest.isEqual(tokenDigest, digest(header.removePrefix("Bearer ")))
    }

    fun browserAuthorized(exchange: HttpExchange): Boolean =
        exchange.requestHeaders["Cookie"].orEmpty().any { header ->
            header.split(';').any {
                val cookie = it.trim()
                val value = if (cookie.substringBefore('=') == "__Host-capri-session") cookie.substringAfter('=', "") else ""
                value.length == browserSession.length &&
                    MessageDigest.isEqual(value.toByteArray(Charsets.US_ASCII), browserSession.toByteArray(Charsets.US_ASCII))
            }
        }

    fun startBrowserSession(exchange: HttpExchange) {
        exchange.responseHeaders.add("Set-Cookie",
            "__Host-capri-session=$browserSession; Path=/; Secure; HttpOnly; SameSite=Strict")
    }

    companion object {
        fun fromEnvironment(): RemoteAccess? {
            val path = System.getenv("CAPRI_TLS_KEYSTORE")
            val password = System.getenv("CAPRI_TLS_PASSWORD")
            val token = System.getenv("CAPRI_ACCESS_TOKEN")
            if (path == null && password == null && token == null) return null
            require(!path.isNullOrBlank() && !password.isNullOrEmpty() && token != null &&
                token.matches(Regex("[A-Za-z0-9_-]{32,256}"))) {
                "Set CAPRI_TLS_KEYSTORE, CAPRI_TLS_PASSWORD and a random URL-safe CAPRI_ACCESS_TOKEN."
            }
            val secret = password.toCharArray()
            val keys = try {
                val store = KeyStore.getInstance("PKCS12")
                Files.newInputStream(Path.of(path)).use { store.load(it, secret) }
                require(store.aliases().toList().any(store::isKeyEntry)) { "TLS keystore has no private key." }
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, secret) }
            } finally {
                secret.fill('\u0000')
            }
            val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            return RemoteAccess(tls, digest(token))
        }

        private fun digest(token: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    }
}
