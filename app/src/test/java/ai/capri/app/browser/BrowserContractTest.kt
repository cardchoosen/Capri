package ai.capri.app.browser

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserContractTest {
    @Test fun strictActionsDoNotAcceptOtherChatsOrFutureOperations() {
        assertEquals("tabs", BrowserContract.validate(JSONObject("""{"action":"tabs"}""")).getString("action"))
        for (raw in listOf("""{"action":"open","url":"https://example.com","chat_id":"other"}""",
            """{"action":"click","ref":"e1"}""", """{"action":"observe","page_id":17}""",
            """{"action":"status","url":"https://example.com"}""", """{"action":"open"}""")) {
            try { BrowserContract.validate(JSONObject(raw)); fail(raw) } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
        assertEquals("browser", BrowserContract.specification().getJSONObject("function").getString("name"))
    }
    @Test fun navigationRejectsLocalFilesCredentialsAndProductionCleartext() {
        assertEquals("https://example.com/", BrowserContract.url("https://example.com", false))
        assertEquals("http://127.0.0.1:8123/", BrowserContract.url("http://127.0.0.1:8123", true))
        for (url in listOf("javascript:alert(1)", "file:///data/data/secret", "content://secret", "https://user:pass@example.com", "http://example.com", "http://127.0.0.1")) {
            try { BrowserContract.url(url, false); fail(url) } catch (_: IllegalArgumentException) { }
        }
        try { BrowserContract.url("http://192.168.1.2", true); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun recoveryMetadataDropsQueryAndFragmentRatherThanPersistingTemporaryTokens() {
        assertEquals("https://example.com/path", BrowserContract.recoveryUrl("https://example.com/path?token=fixture#private"))
    }
}
