package ai.capri.app.browser

import ai.capri.app.Workspace
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class BrowserFilePolicyTest {
    @Test fun namesAndMimeFiltersCannotEscapeTheOutputDirectory() {
        assertEquals("proof.txt",BrowserFilePolicy.name("../../proof.txt"))
        assertFalse(BrowserFilePolicy.name("..\\evil/../../secret").contains('/'))
        assertTrue(BrowserFilePolicy.accepts("text/plain","test.txt",listOf(".txt")))
        assertTrue(BrowserFilePolicy.accepts("image/png","p.png",listOf("image/*")))
        assertFalse(BrowserFilePolicy.accepts("application/octet-stream","secret.bin",listOf(".txt")))
    }
    @Test fun uploadsOnlyResolveExistingCurrentWorkspaceFiles() {
        val root=Files.createTempDirectory("browser-policy").toFile()
        try {
            val w=Workspace(root);w.file("a","ok.txt").writeText("test")
            assertEquals("ok.txt",BrowserFilePolicy.source(w,"a","ok.txt").name)
            for(p in listOf("../other","/data/private","file://secret","missing.txt"))
                assertThrows(IllegalArgumentException::class.java){BrowserFilePolicy.source(w,"a",p)}
            Files.createSymbolicLink(w.file("a","escape").toPath(),root.toPath())
            assertThrows(IllegalArgumentException::class.java){BrowserFilePolicy.source(w,"a","escape/secret")}
        }finally{root.deleteRecursively()}
    }
}
