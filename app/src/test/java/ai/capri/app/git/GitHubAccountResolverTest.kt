package ai.capri.app.git

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GitHubAccountResolverTest {
    private fun client(code: Int, body: String, count: AtomicInteger = AtomicInteger()) = OkHttpClient.Builder()
        .followRedirects(false).addInterceptor { chain ->
            count.incrementAndGet()
            val request = chain.request()
            assertEquals("https://api.github.com/user", request.url.toString())
            assertTrue(request.header("Authorization") == "Bearer fixture-token")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .header("Location", "https://other.invalid/user").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    @Test fun resolvesLoginRatherThanDisplayName() = runBlocking {
        assertEquals("actual-login", GitHubAccountResolver.username("fixture-token", client(200, """{"login":"actual-login","name":"Display Name"}""")))
    }
    @Test fun failuresNeverExposeResponseBodyOrToken() = runBlocking {
        for ((code, body) in listOf(401 to "fixture-token", 403 to "fixture-token", 200 to "invalid fixture-token", 200 to "{}", 200 to """{"login":"a\npassword=fixture-token"}""")) {
            try { GitHubAccountResolver.username("fixture-token", client(code, body)); fail("Expected failure") }
            catch (error: IllegalArgumentException) { assertFalse(error.message.orEmpty().contains("fixture-token")) }
        }
    }
    @Test fun redirectsAreNotAcceptedAndResponsesAreBounded() = runBlocking {
        val count = AtomicInteger()
        try { GitHubAccountResolver.username("fixture-token", client(302, "", count)); fail("Expected failure") } catch (_: IllegalArgumentException) { }
        assertEquals(1, count.get())
        try { GitHubAccountResolver.username("fixture-token", client(200, "x".repeat(16385))); fail("Expected failure") } catch (_: IllegalArgumentException) { }
    }
}
