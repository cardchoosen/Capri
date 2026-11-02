package ai.capri.app.extensions.hub

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** A transient preview request, never reconstructed from durable contribution receipts at startup. */
internal class HubApprovalPrompt {
    data class Request(val chat: String, val key: String)
    private val current = MutableStateFlow<Request?>(null)
    val requests = current.asStateFlow()
    fun open(chat: String, record: JSONObject): Boolean {
        if(record.optBoolean("acknowledged") || record.optString("state") !in setOf("awaiting_user", "approved", "failed", "publishing")) return false
        val key = record.getString("key")
        require(record.getString("chat") == chat && key.matches(Regex("[a-f0-9]{64}"))) { "Preview must match the contribution's owning chat." }
        current.value = Request(chat,key)
        return true
    }
    fun dismiss(chat: String, key: String) {
        current.compareAndSet(Request(chat,key),null)
    }
}
