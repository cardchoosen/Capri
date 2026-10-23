package ai.capri.app

import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Never follow symlinks when measuring or removing app-owned trees. */
internal object RuntimeFiles {
    fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun size(file: File): Long {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0
        var size = 0L
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) size += attrs.size(); return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(path: Path, error: java.io.IOException?) = FileVisitResult.CONTINUE
        })
        return size
    }
    fun delete(file: File) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(path); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error; Files.delete(path); return FileVisitResult.CONTINUE
            }
        })
    }
}
