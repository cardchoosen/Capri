package ai.capri.app

import ai.capri.app.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class ModelApiTest {
    @Test fun officialBalanceUsesMatchingAccountAndDoesNotInventZeroOnFailure() = runBlocking {
        val seen = mutableListOf<okhttp3.Request>()
        var body = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.34"}]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = ModelApi(client)
        val account = ModelAccount(provider = "deepseek", name = "fixture", endpoint = "https://api.deepseek.com/v1", key = "balance-fixture", protocol = ModelProtocol.CHAT)
        assertEquals("CNY 12.34", api.balance(account))
        assertEquals("/user/balance", seen.single().url.encodedPath)
        assertEquals("Bearer balance-fixture", seen.single().header("Authorization"))
        body = "{}"
        assertTrue(runCatching { api.balance(account) }.isFailure)
        assertFalse(ModelApi.errorLabel(IllegalStateException(), listing = false).contains("模型"))
    }
    @Test fun metadataAuthPaginationRedirectsAndBalanceBoundary() = runBlocking {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer(); server.useHttps(serverCert.sslSocketFactory(), false); server.start()
        try {
            val api = ModelApi(OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build())
            val account = ModelAccount(provider = "claude", name = "fixture", endpoint = server.url("/v1").toString(), key = "fixture-key", protocol = ModelProtocol.ANTHROPIC)
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"a","max_input_tokens":1048576}],"has_more":true,"last_id":"a"}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"b"}],"has_more":false}"""))
            assertEquals(2, api.models(account).size)
            val first = server.takeRequest(); val second = server.takeRequest()
            assertEquals("fixture-key", first.getHeader("x-api-key")); assertNull(first.getHeader("Authorization"))
            assertTrue(first.path!!.startsWith("/v1/models?limit=100")); assertTrue(second.path!!.contains("after_id=a"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/leak")))
            try { api.models(account); fail() } catch (e: ModelHttpError) { assertEquals(302, e.code) }
            assertEquals(3, server.requestCount); server.takeRequest()
            try { api.balance(account.copy(provider = "deepseek")); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(3, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/g","inputTokenLimit":1000000}]}"""))
            assertEquals(1_000_000, api.models(account.copy(protocol = ModelProtocol.GEMINI)).single().context)
            val google = server.takeRequest(); assertEquals("fixture-key", google.getHeader("x-goog-api-key")); assertNull(google.getHeader("Authorization"))
        } finally { server.shutdown() }
    }
}
