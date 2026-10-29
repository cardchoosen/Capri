package ai.capri.app

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files

/** A durable cleanup receipt: deleting the session index never makes partial cleanup disappear. */
internal class SessionDeletion(private val root: File) {
    private fun checked(relative: String): File {
        var file = root
        for (part in relative.split('/')) {
            require(part.isNotBlank() && part != "." && part != "..")
            file = File(file, part)
            check(!Files.isSymbolicLink(file.toPath())) { "会话目录存在符号链接，清理已暂停" }
        }
        return file
    }
    private fun marker(chat: String): AtomicFile {
        require(chat.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(checked("session-deletions/$chat.json"))
    }
    fun pending(): Set<String> = checked("session-deletions").listFiles().orEmpty().mapNotNull {
        it.name.removeSuffix(".bak").removeSuffix(".new").takeIf { n -> n.endsWith(".json") }
            ?.removeSuffix(".json")?.takeIf { id -> id.matches(Regex("[a-zA-Z0-9-]+")) }
    }.toSet()
    fun begin(chat: String) {
        val file = marker(chat); check(file.baseFile.parentFile!!.mkdirs() || file.baseFile.parentFile!!.isDirectory)
        val output = file.startWrite()
        try { output.write("cleanup-v1".toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    fun finish(chat: String) { marker(chat).delete() }
    private fun targets(chat: String): List<File> {
        require(chat.matches(Regex("[a-zA-Z0-9-]+")))
        return listOf("history-backups/$chat", "sessions/$chat", "context-history/$chat", "web-retrieval/$chat", "agent-recovery/$chat", "agent-recovery/$chat.json", "agent-recovery/$chat.json.bak", "agent-recovery/$chat.json.new").map(::checked)
    }
    fun validate(chat: String) { targets(chat) }
    fun cleanup(chat: String) { targets(chat).forEach(RuntimeFiles::delete) }
}
