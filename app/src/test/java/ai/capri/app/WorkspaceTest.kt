package ai.capri.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WorkspaceTest {
    @Test fun sessionsCannotReadEachOthersFiles() {
        val directory = Files.createTempDirectory("capri-test").toFile()
        try {
            val workspace = Workspace(directory)
            workspace.file("one", "notes.txt").writeText("private")
            assertFalse(workspace.file("two", "notes.txt").exists())
            assertEquals("private", workspace.file("one", "notes.txt").readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun pathsCannotEscapeWorkspaceIncludingSymlinks() {
        val directory = Files.createTempDirectory("capri-test").toFile()
        try {
            val workspace = Workspace(directory)
            val outside = workspace.folder("two", "workspace")
            Files.createSymbolicLink(workspace.folder("one", "workspace").resolve("escape").toPath(), outside.toPath())
            for (path in listOf("../home/secret", "/etc/passwd", "escape/secret")) {
                try { workspace.file("one", path); fail("accepted $path") } catch (_: IllegalArgumentException) { }
            }
        } finally { directory.deleteRecursively() }
    }
}
