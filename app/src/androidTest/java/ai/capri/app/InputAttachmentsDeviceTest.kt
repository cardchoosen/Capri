package ai.capri.app

import ai.capri.app.knowledge.ContextHistory
import ai.capri.app.model.ModelWire
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InputAttachmentsDeviceTest {
    @Test fun fileAndImageInputsAreScopedPersistentAndHydrated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "inputs-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val workspace = Workspace(File(root, "sessions"))
            var store = InputAttachments(File(root, "drafts"), workspace)
            val text = store.import("chat-a", "notes.txt", ByteArrayInputStream("User document fixture".toByteArray()))
            val second = store.import("chat-a", "notes.txt", ByteArrayInputStream("Second fixture".toByteArray()))
            assertNotEquals(text.getString("path"), second.getString("path"))
            val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(173, 40, 211)) }
            val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }; bitmap.recycle()
            val picture = store.import("chat-a", "image.png", ByteArrayInputStream(bytes))
            assertEquals("image/png", picture.getString("mime")); assertEquals(48, picture.getInt("width"))
            store = InputAttachments(File(root, "drafts"), workspace)
            assertEquals(3, store.list("chat-a").size); assertTrue(store.list("chat-b").isEmpty())
            try { store.select("chat-b", listOf(picture.getString("id"))); fail() } catch (_: IllegalStateException) {}
            val files = store.select("chat-a", listOf(text.getString("id"), picture.getString("id")))
            val entry = Entry("user", "Read these", inputs = JSONArray(files).toString())
            val history = ContextHistory(File(root, "history")).fresh("chat-a", listOf(entry), "rules")
            assertTrue(history.getJSONObject(1).getString("content").replace("\\/", "/").contains("/workspace/" + text.getString("path")))
            assertFalse(history.toString().contains("User document fixture"))
            val before = history.toString(); val hydrated = store.hydrate("chat-a", history)
            assertEquals(before, history.toString())
            assertEquals(1, hydrated.getJSONObject(1).getJSONArray("_capri_images").length())
            assertFalse(RecoveryMessages.sanitized(history).toString().contains("base64"))
            val body = ModelWire.request(ModelConfig(protocol = "CHAT"), hydrated, JSONArray(), 1024)
            assertTrue(body.toString().replace("\\/", "/").contains("data:image/jpeg;base64,"))
            assertFalse(body.toString().contains(InputAttachments.KEY))
            store.consume("chat-a", files.map { it.getString("id") }.toSet())
            assertEquals(1, store.list("chat-a").size)
            assertTrue(workspace.file("chat-a", picture.getString("path")).isFile)
            assertEquals(1, store.hydrate("chat-a", history).getJSONObject(1).getJSONArray("_capri_images").length())
            val imageFile = workspace.file("chat-a", picture.getString("path")); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); imageFile.writeBytes(bytes)
            try { store.hydrate("chat-a", history); fail() } catch (_: InputAttachmentFailure) {}
            store.remove("chat-a", second.getString("id")); assertTrue(store.list("chat-a").isEmpty())
            assertFalse(workspace.file("chat-a", second.getString("path")).exists())
            try { store.import("chat-a", "empty.txt", ByteArrayInputStream(byteArrayOf())); fail() } catch (_: IllegalArgumentException) {}
            repeat(8) { store.import("chat-a", "sample.txt", ByteArrayInputStream(byteArrayOf(1))) }
            try { store.import("chat-a", "ninth.txt", ByteArrayInputStream(byteArrayOf(1))); fail() } catch (_: IllegalArgumentException) {}
            assertEquals(8, store.list("chat-a").size)
        } finally { root.deleteRecursively() }
    }
}
