package ai.capri.app.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal class RemoteChatClient(address: String, private val token: String, certificate: ByteArray?) {
    private val endpoint = requireNotNull(normalizeAddress(address).toHttpUrlOrNull()) { "Enter a valid HTTPS service address." }
        .also {
            require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() &&
                it.encodedPath == "/" && it.query == null && it.fragment == null) { "Enter the HTTPS service origin." }
        }.newBuilder().addPathSegments("api/chat").build()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(55, TimeUnit.SECONDS).apply {
            if (certificate != null) {
                val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null)
                    setCertificateEntry("capri-remote-ca", parseCa(certificate))
                }
                val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
                val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
                val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
                sslSocketFactory(tls.socketFactory, manager)
            }
        }.build()

    init {
        require(token.matches(Regex("[A-Za-z0-9_-]{32,256}"))) { "Enter a valid service access token." }
    }

    data class Message(val role: String, val content: String)

    fun history(): List<Message> {
        val messages = request().getJSONArray("messages")
        return List(messages.length()) { index ->
            val entry = messages.getJSONObject(index)
            Message(entry.getString("role"), entry.getString("content"))
        }
    }

    fun send(text: String) {
        require(text.isNotBlank() && text.length <= 4_000)
        val result = request(JSONObject().put("text", text))
        check(result.getString("outcome") == "complete") { "The remote turn did not complete." }
    }

    fun device(operation: String, body: JSONObject): JSONObject {
        require(operation == "poll" || operation == "result")
        val url = endpoint.newBuilder().removePathSegment(1).addPathSegments("device/$operation").build()
        return request(body, url)
    }

    private fun request(body: JSONObject? = null, url: okhttp3.HttpUrl = endpoint): JSONObject {
        val request = Request.Builder().url(url).header("X-Capri-Chat", "1")
            .header("Authorization", "Bearer $token").apply {
                if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
            }.build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Capri service returned HTTP ${response.code}." }
            val source = checkNotNull(response.body).source()
            val limit = 8L * 1024 * 1024
            check(!source.request(limit + 1)) { "The service response is too large." }
            JSONObject(source.readUtf8())
        }
    }

    companion object {
        fun normalizeAddress(address: String): String =
            Normalizer.normalize(address.trim(), Normalizer.Form.NFKC)

        fun parseCa(bytes: ByteArray): X509Certificate {
            require(bytes.size in 1..16_384) { "The CA certificate is too large or empty." }
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(bytes.inputStream()) as X509Certificate
            require(certificate.basicConstraints >= 0) { "Select a CA certificate." }
            certificate.checkValidity()
            return certificate
        }
    }
}
