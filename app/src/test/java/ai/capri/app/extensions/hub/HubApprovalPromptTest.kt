package ai.capri.app.extensions.hub

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HubApprovalPromptTest {
    private fun record(chat: String = "chat", state: String = "awaiting_user") = JSONObject()
        .put("chat",chat).put("key","a".repeat(64)).put("state",state)
    @Test fun pendingReceiptsNeverReopenOnStartupOrAfterDismissal() {
        val pending = record()
        val prompt = HubApprovalPrompt()
        assertNull(prompt.requests.value)
        assertTrue(prompt.open("chat",pending)) // A fresh prepare or an explicit record selection.
        prompt.dismiss("other",pending.getString("key"))
        assertEquals("chat",prompt.requests.value!!.chat)
        prompt.dismiss("chat",pending.getString("key"))
        assertNull(prompt.requests.value)
        assertEquals("awaiting_user",pending.getString("state"))
        val restarted = HubApprovalPrompt()
        assertNull(restarted.requests.value) // Durable receipts are not a source of automatic prompts.
        assertTrue(restarted.open("chat",pending))
    }
    @Test fun publishedOrDeclinedReceiptsCannotRequestAnotherApproval() {
        val prompt = HubApprovalPrompt()
        for(state in listOf("submitted","declined"))assertFalse(prompt.open("chat",record(state=state)))
        assertFalse(prompt.open("chat",record().put("acknowledged",true)))
        assertNull(prompt.requests.value)
        try { prompt.open("other",record());fail() } catch(_: IllegalArgumentException) {}
        assertNull(prompt.requests.value)
    }
}
