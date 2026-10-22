package ai.capri.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class DebugPayloadTest {
    @Test fun preservesSourceCodeAndUnicodeThroughContentCliPayload() {
        val text = "中文🙂\nprint(\"literal \\n\"); path='a:b'; echo '\$HOME'"
        val payload = Base64.getEncoder().encodeToString(JSONObject().put("text", text).put("offset", 3).toString().toByteArray())
        val decoded = DebugPayload.decode(payload)
        assertEquals(text, decoded.getString("text"))
        assertEquals(3, decoded.getInt("offset"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedPayload() {
        DebugPayload.decode("a".repeat(64001))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidEncoding() {
        DebugPayload.decode("%bad encoding")
    }
}
