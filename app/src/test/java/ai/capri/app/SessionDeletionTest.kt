package ai.capri.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionDeletionTest {
    @Test fun deletesOnlyNamedSessionAndItsArchivesAndIsRepeatable() {
        val root = Files.createTempDirectory("capri-delete").toFile()
        try {
            for (name in listOf("sessions/target/workspace/a", "context-history/target/summary.txt", "web-retrieval/target/source.json", "agent-recovery/target.json",
                "sessions/other/workspace/a", "runtime/ubuntu/a", "ssh/key")) File(root, name).apply { parentFile!!.mkdirs(); writeText(name) }
            val deletion = SessionDeletion(root)
            deletion.cleanup("target"); deletion.cleanup("target")
            assertFalse(File(root, "sessions/target").exists())
            assertFalse(File(root, "context-history/target").exists())
            assertFalse(File(root, "web-retrieval/target").exists())
            assertFalse(File(root, "agent-recovery/target.json").exists())
            for (name in listOf("sessions/other/workspace/a", "runtime/ubuntu/a", "ssh/key")) assertEquals(name, File(root, name).readText())
        } finally { RuntimeFiles.delete(root) }
    }
    @Test fun childLinksAreUnlinkedWithoutDeletingTheirTargets() {
        val root = Files.createTempDirectory("capri-delete-links").toFile()
        try {
            val other = File(root, "other").apply { mkdirs() }; File(other, "keep").writeText("keep")
            val target = File(root, "sessions/target/workspace").apply { mkdirs() }
            Files.createSymbolicLink(File(target, "external").toPath(), other.toPath())
            SessionDeletion(root).cleanup("target")
            assertEquals("keep", File(other, "keep").readText())
        } finally { RuntimeFiles.delete(root) }
    }
    @Test fun parentLinksAndInvalidIdsAreRejectedBeforeDeletion() {
        val root = Files.createTempDirectory("capri-delete-guard").toFile()
        try {
            val other = File(root, "other").apply { mkdirs() }; File(other, "keep").writeText("keep")
            Files.createSymbolicLink(File(root, "sessions").toPath(), other.toPath())
            assertTrue(runCatching { SessionDeletion(root).cleanup("target") }.isFailure)
            assertTrue(runCatching { SessionDeletion(root).cleanup("../other") }.isFailure)
            assertEquals("keep", File(other, "keep").readText())
        } finally { RuntimeFiles.delete(root) }
    }
}
