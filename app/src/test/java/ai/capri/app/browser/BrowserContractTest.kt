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
    @Test fun nestedTimeoutIsNormalizedWithoutDiscardingTheCondition() {
        val raw=JSONObject().put("action","wait").put("page_id","12345678-1234-1234-1234-123456789abc")
            .put("expect",JSONObject().put("text","actual evidence").put("timeout_ms",1500))
        val fixed=BrowserContract.validate(raw)
        assertEquals(1500,fixed.getInt("timeout_ms"))
        assertEquals("actual evidence",fixed.getJSONObject("expect").getString("text"))
        assertFalse(fixed.getJSONObject("expect").has("timeout_ms"))
        assertTrue(raw.getJSONObject("expect").has("timeout_ms")) // Caller input is immutable.
        BrowserContract.validate(JSONObject(raw.toString()).put("timeout_ms",1500))
        assertThrows(IllegalArgumentException::class.java){BrowserContract.validate(JSONObject(raw.toString()).put("timeout_ms",2000))}
        for(value in listOf<Any>("1500",2.5,0,15001)) {
            val bad=JSONObject(raw.toString());bad.getJSONObject("expect").put("timeout_ms",value)
            assertThrows(Exception::class.java){BrowserContract.validate(bad)}
        }
    }
    @Test fun emptyOptionalExpectationIsNotAWaitAndUnknownConditionsAreNotSilentlyIgnored() {
        val id="12345678-1234-1234-1234-123456789abc"
        val scroll=JSONObject().put("action","scroll").put("page_id",id).put("request_id",id).put("direction","down")
        for(empty in listOf<Any>(JSONObject(),JSONObject.NULL)) {
            assertFalse(BrowserContract.validate(JSONObject(scroll.toString()).put("expect",empty)).has("expect"))
            assertThrows(Exception::class.java){BrowserContract.validate(JSONObject().put("action","wait").put("page_id",id).put("expect",empty))}
        }
        val bad=JSONObject(scroll.toString()).put("expect",JSONObject().put("selector","private-condition-value"))
        val exception=assertThrows(Exception::class.java){BrowserContract.validate(bad)}
        val error=BrowserContract.validationError(bad,exception)
        assertEquals("not_applied",error.getString("action_state"));assertTrue(error.getBoolean("safe_to_retry"))
        assertTrue(error.toString().contains("selector"));assertFalse(error.toString().contains("private-condition-value"))
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
