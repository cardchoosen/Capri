package ai.capri.app.network

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NetworkReachabilityTest {
    @Test fun targetsDoNotLeakCredentialsQueriesOrPaths() {
        assertEquals("https://example.com:8443/", NetworkReachability.origin("https://example.com:8443/private?q=secret#token"))
        for (url in listOf("http://example.com", "file:///tmp/test", "https://user:secret@example.com", "not a url"))
            assertThrows(IllegalArgumentException::class.java) { NetworkReachability.origin(url) }
    }
    @Test fun partialReachabilityCannotBecomeGlobalSuccess() {
        assertEquals("unknown", NetworkReachability.sampleState(emptyList()))
        assertEquals("partial", NetworkReachability.sampleState(listOf(true, false)))
        assertEquals("unreachable", NetworkReachability.sampleState(listOf(false, false)))
        assertEquals("reachable", NetworkReachability.sampleState(listOf(true, true)))
    }
    @Test fun dnsTlsAndTimeoutHaveDistinctEvidence() {
        assertEquals("dns_failed", NetworkReachability.failureKind(UnknownHostException()))
        assertEquals("tls_failed", NetworkReachability.failureKind(SSLHandshakeException("failed")))
        assertEquals("timeout", NetworkReachability.failureKind(SocketTimeoutException()))
        assertEquals("connect_failed", NetworkReachability.failureKind(ConnectException()))
        assertEquals("network_error", NetworkReachability.failureKind(java.io.IOException()))
    }
}
