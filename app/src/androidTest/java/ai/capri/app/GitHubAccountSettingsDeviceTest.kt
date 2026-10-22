package ai.capri.app

import ai.capri.app.git.GitAccounts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GitHubAccountSettingsDeviceTest {
    @Test fun tokenOnlySaveResolvesIdentityAndFailedReplacementKeepsPreviousAccount() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preference = "git-account-settings-test-${java.util.UUID.randomUUID()}"
        val accounts = GitAccounts(context, preference)
        try {
            var requests = 0
            val initial = accounts.saveFromSettings("https://GitHub.com:443/", "", "fixture-first-token") {
                requests++; assertTrue(it == "fixture-first-token"); "first-user"
            }
            assertEquals("first-user", initial.username)
            val unchanged = accounts.saveFromSettings("github.com", "", "") { error("Blank edit must not request GitHub") }
            assertEquals("first-user", unchanged.username)
            assertEquals(1, requests)
            try {
                accounts.saveFromSettings("github.com", "", "fixture-bad-token") { throw IllegalArgumentException("Token 无效") }
                fail("Expected failure")
            } catch (_: IllegalArgumentException) { }
            assertTrue(accounts.secret("github.com")?.token == "fixture-first-token")
            assertEquals("first-user", accounts.list().single().username)
            try {
                accounts.saveFromSettings("github.com", "", "fixture-cancelled-token") { throw CancellationException() }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertTrue(accounts.secret("github.com")?.token == "fixture-first-token")
            accounts.saveFromSettings("github.com", "ignored", "fixture-second-token") { "second-user" }
            assertEquals("second-user", accounts.list().single().username)
            assertTrue(accounts.secret("github.com")?.token == "fixture-second-token")
            // Older accounts still work without revalidation when editing with an empty token.
            accounts.save("github.com", "legacy-user", "fixture-legacy-token")
            accounts.saveFromSettings("github.com", "", "") { error("Legacy edit must stay offline") }
            assertEquals("legacy-user", accounts.list().single().username)
            accounts.saveFromSettings("git.example.invalid", "custom-user", "fixture-custom-token") { error("Other hosts must not contact GitHub") }
            assertEquals("custom-user", accounts.list().first { it.host == "git.example.invalid" }.username)
            try { accounts.saveFromSettings("git.other.invalid", "", "fixture-token"); fail("Other host still needs username") }
            catch (_: IllegalArgumentException) { }
        } finally { context.deleteSharedPreferences(preference) }
    }
}
