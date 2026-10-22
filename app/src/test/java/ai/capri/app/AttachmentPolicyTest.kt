package ai.capri.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AttachmentPolicyTest {
    @Test fun translatesOnlyTheGuestWorkspacePrefix() {
        assertEquals("plots/sort.gif", Attachments.relativePath("/workspace/plots/sort.gif"))
        assertEquals("sort.gif", Attachments.relativePath("sort.gif"))
        // Workspace.file remains responsible for canonical boundary and symlink checks.
        assertEquals("/etc/passwd", Attachments.relativePath("/etc/passwd"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsRemoteUrls() { Attachments.relativePath("https://example.com/a.gif") }
    @Test fun deduplicationIsScopedToCurrentTurnAndFileVersion() {
        val item = JSONObject().put("path", "a.gif").put("size", 10).put("modified_at", 123)
        val sent = Entry("attachment", item.toString())
        assertTrue(Attachments.existing(listOf(Entry("user", "task"), sent), item))
        assertFalse(Attachments.existing(listOf(sent, Entry("user", "new task")), item))
        assertFalse(Attachments.existing(listOf(Entry("user", "task"), sent), JSONObject(item.toString()).put("modified_at", 124)))
    }
}
