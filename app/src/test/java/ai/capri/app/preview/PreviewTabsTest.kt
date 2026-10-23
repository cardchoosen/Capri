package ai.capri.app.preview

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PreviewTabsTest {
    @Test fun backgroundBrowserUpdatesNeverSelectOrStealFilePreview() {
        val tabs = PreviewTabs()
        assertTrue(tabs.tabs("a").isEmpty()); assertEquals(PreviewTabs.CHAT, tabs.selected("a"))
        tabs.syncBrowser("a", listOf("p1" to "Page 1"))
        assertEquals(PreviewTabs.CHAT, tabs.selected("a"))
        tabs.openFile("a", "src/main.cpp")
        val file = tabs.selected("a")
        tabs.syncBrowser("a", listOf("p1" to "Renamed", "p2" to "Page 2"))
        assertEquals(file, tabs.selected("a")); assertEquals(3, tabs.tabs("a").size)
    }
    @Test fun conversationCannotCloseAndLastPreviewReturnsToConversation() {
        val tabs = PreviewTabs(); tabs.openFile("a", "main.cpp")
        tabs.close("a", PreviewTabs.CHAT); assertEquals("file:main.cpp", tabs.selected("a"))
        tabs.close("a", "file:main.cpp"); assertTrue(tabs.tabs("a").isEmpty()); assertEquals(PreviewTabs.CHAT, tabs.selected("a"))
    }
    @Test fun sessionsKeepIndependentSelectionAndFilePositions() {
        val tabs = PreviewTabs(); tabs.openFile("a", "a.cpp"); tabs.rememberPosition("a", "file:a.cpp", 400)
        tabs.syncBrowser("b", listOf("p1" to "Page")); assertEquals(PreviewTabs.CHAT, tabs.selected("b"))
        tabs.select("b", PreviewTabs.web("p1")); assertEquals("file:a.cpp", tabs.selected("a")); assertEquals(400, tabs.position("a", "file:a.cpp"))
        tabs.syncBrowser("b", emptyList()); assertEquals(PreviewTabs.CHAT, tabs.selected("b"))
    }
    @Test fun textPreviewIsBoundedAndRejectsBinaryWithoutModifyingFiles() {
        val file = Files.createTempFile("capri-preview", ".cpp").toFile()
        try {
            file.writeText("int main() {} // 摩羯")
            assertEquals(file.readText(), PreviewText.read(file))
            file.writeBytes(byteArrayOf(0, 1, 2)); try { PreviewText.read(file); fail("binary accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(3, file.length().toInt())
            file.writeBytes(ByteArray(256001) { 65 }); try { PreviewText.read(file); fail("oversize accepted") } catch (_: IllegalArgumentException) { }
        } finally { file.delete() }
    }
}
