package ai.capri.app

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

class ModelRequestsTest {
    @Test fun connectionRetriesNeverReplayEarlierToolEffectsAndStayBounded() = runBlocking {
        var toolExecutions = 0
        val policy = ModelRequests(pause = {})
        policy.execute { JSONObject().put("tool", "run") }
        toolExecutions++
        var attempts = 0
        val result = policy.execute {
            attempts++
            if (attempts < 3) throw IOException("abort")
            JSONObject().put("reply", "done")
        }
        assertEquals("done", result.getString("reply"))
        assertEquals(1, toolExecutions)
        assertEquals(4, policy.used)
        try { policy.execute { throw IOException("abort") }; fail() } catch (_: ModelRequestFailure) { }
        assertEquals(7, policy.used)
    }
    @Test fun authTlsAndInvalidResponsesDoNotRetryAndRetryAfterIsHonored() = runBlocking {
        for (error in listOf(ModelHttpError(401), SSLHandshakeException("bad cert"), IllegalArgumentException())) {
            val policy = ModelRequests(pause = {})
            try { policy.execute { throw error }; fail() } catch (_: ModelRequestFailure) { }
            assertEquals(1, policy.used)
        }
        val pauses = mutableListOf<Long>()
        val policy = ModelRequests(pause = { pauses += it })
        var tries = 0
        policy.execute { if (tries++ == 0) throw ModelHttpError(429, 2000); JSONObject() }
        assertEquals(listOf(2000L), pauses)
        val longWait = ModelRequests(pause = { fail("must not wait/retry") })
        try { longWait.execute { throw ModelHttpError(429, 60_000) }; fail() } catch (_: ModelRequestFailure) { }
        assertEquals(1, longWait.used)
    }
    @Test fun budgetIncludesRetriesAndCancellationStopsImmediately() = runBlocking {
        val policy = ModelRequests(maximum = 2, pause = {})
        var calls = 0
        try { policy.execute { calls++; throw IOException() }; fail() } catch (_: ModelRequestFailure) { }
        assertEquals(2, calls)
        try { policy.execute { calls++; JSONObject() }; fail() } catch (_: ModelRequestFailure) { }
        assertEquals(2, calls)
        val cancel = ModelRequests(pause = { throw CancellationException() })
        try { cancel.execute { throw IOException() }; fail() } catch (_: CancellationException) { }
        assertEquals(1, cancel.used)
    }
}
