package ai.capri.app

import ai.capri.app.model.ModelWire
import ai.capri.app.model.ContextTokens
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InputMessageTest {
    private fun image() = JSONObject().put("mime", "image/jpeg").put("data", "aGVsbG8=")
    @Test fun fourProtocolsCarryImageBytesWithoutSendingPrivateInputMetadata() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Describe the image")
            .put("_capri_images", JSONArray().put(image())).put(InputAttachments.KEY, JSONArray().put(JSONObject().put("width", 32))))
        for (protocol in listOf("CHAT", "RESPONSES", "ANTHROPIC", "GEMINI")) {
            val result = ModelWire.request(ModelConfig(protocol = protocol), messages, JSONArray(), 1024)
            val text = result.toString()
            assertTrue(protocol, text.contains("aGVsbG8="))
            assertFalse(protocol, text.contains("_capri_images"))
            assertFalse(protocol, text.contains(InputAttachments.KEY))
            when(protocol) {
                "CHAT" -> assertEquals("image_url", result.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
                "RESPONSES" -> assertEquals("input_image", result.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
                "ANTHROPIC" -> assertEquals("base64", result.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1).getJSONObject("source").getString("type"))
                "GEMINI" -> assertEquals("image/jpeg", result.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))
            }
        }
    }
    @Test fun inputReferencesSurviveHistoryStorageAndPortableRecoveryWithoutImageBytes() {
        val files = JSONArray().put(JSONObject().put("id", "fixture").put("path", "inputs/fixture/image.png").put("width", 32))
        val entry = Entry("user", "question", inputs = files.toString())
        val chat = Session(messages = mutableListOf(entry))
        val restored = SessionCatalog.decode(SessionCatalog.encode(listOf(chat))).single { it.id == chat.id }.messages.single()
        assertEquals(entry.inputs, restored.inputs)
        val message = JSONObject().put("role", "user").put("content", "question").put(InputAttachments.KEY, files)
        val plain = JSONObject().put("role", "user").put("content", "question")
        assertTrue(ContextTokens.message(message) >= ContextTokens.message(plain) + 4096)
        assertTrue(ModelWire.portable(JSONArray().put(message)).getJSONObject(0).has(InputAttachments.KEY))
        assertFalse(RecoveryMessages.sanitized(JSONArray().put(message)).toString().contains("data:image"))
    }
}
