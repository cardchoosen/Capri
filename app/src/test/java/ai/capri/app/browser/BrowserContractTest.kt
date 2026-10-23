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
    @Test fun writesRequireFreshReferencesAndStrictParameters() {
        val id="12345678-1234-1234-1234-123456789abc"
        val click=JSONObject().put("action","click").put("page_id",id).put("request_id",id)
            .put("snapshot_id",id).put("document_epoch",1).put("ref","e1")
        assertEquals("click",BrowserContract.validate(click).getString("action"))
        for(key in listOf("snapshot_id","document_epoch","request_id","ref")) {
            val bad=JSONObject(click.toString());bad.remove(key)
            assertThrows(Exception::class.java) { BrowserContract.validate(bad) }
        }
        for(bad in listOf(JSONObject(click.toString()).put("document_epoch",1.5),
            JSONObject(click.toString()).put("ref","#button"),
            JSONObject(click.toString()).put("timeout_ms",16000),
            JSONObject(click.toString()).put("expect",JSONObject().put("selector","#secret"))))
            assertThrows(Exception::class.java) { BrowserContract.validate(bad) }
        BrowserContract.validate(JSONObject(click.toString()).put("expect",JSONObject().put("text","done").put("url_contains","/done")))
        assertEquals("",BrowserContract.validate(JSONObject(click.toString()).put("action","fill").put("text","")).getString("text"))
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
