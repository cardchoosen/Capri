package ai.capri.app.git

import org.junit.Assert.*
import org.junit.Test

class GitCredentialPolicyTest {
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }
    @Test fun hostsAreExactHttpsOriginsNotUrlsContainingCredentials() {
        assertEquals("github.com", GitCredentialPolicy.host("https://GitHub.com:443/"))
        assertEquals("git.example:8443", GitCredentialPolicy.host("git.example:8443"))
        assertEquals("[::1]:8443", GitCredentialPolicy.host("https://[::1]:8443"))
        rejects { GitCredentialPolicy.host("http://github.com") }
        rejects { GitCredentialPolicy.host("https://user:token@github.com") }
        rejects { GitCredentialPolicy.host("github.com/repo") }
        rejects { GitCredentialPolicy.host("github.com?x=y") }
        rejects { GitCredentialPolicy.host("github.com\nother") }
        assertNull(GitCredentialPolicy.requestedHost("http", "github.com"))
        assertNotEquals(GitCredentialPolicy.host("github.com"), GitCredentialPolicy.host("github.com.evil.invalid"))
    }
    @Test fun usernamesAndTokensCannotInjectCredentialProtocolFields() {
        assertEquals("alice", GitCredentialPolicy.username(" alice "))
        rejects { GitCredentialPolicy.username("alice\npassword=x") }
        rejects { GitCredentialPolicy.username("a:b") }
        rejects { GitCredentialPolicy.token("a\nb") }
        rejects { GitCredentialPolicy.token("a b") }
        rejects { GitCredentialPolicy.token("") }
    }
    @Test fun repositoryProbeCannotSendCredentialsToOtherOrigins() {
        assertEquals("/owner/repo.git", GitCredentialPolicy.repository("https://github.com/owner/repo.git", "github.com").encodedPath)
        rejects { GitCredentialPolicy.repository("https://github.com.evil.invalid/owner/repo.git", "github.com") }
        rejects { GitCredentialPolicy.repository("https://github.com:444/owner/repo.git", "github.com") }
        rejects { GitCredentialPolicy.repository("http://github.com/owner/repo.git", "github.com") }
        rejects { GitCredentialPolicy.repository("https://alice:secret@github.com/owner/repo.git", "github.com") }
        rejects { GitCredentialPolicy.repository("https://github.com/owner/repo.git?x=y", "github.com") }
    }
}
