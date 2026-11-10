package ai.capri.host

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal class HostSessions(root: Path, legacy: Path?) : AutoCloseable {
    private val sessions = root.resolve("sessions")
    private val owner: FileChannel

    init {
        Files.createDirectories(sessions, privateDirectory)
        owner = FileChannel.open(root.resolve("host.lock"), setOf(CREATE, WRITE), privateFile)
        try {
            check(owner.tryLock() != null) { "The Capri data directory is already in use." }
            val initial = directory(DEFAULT)
            if (Files.notExists(initial, NOFOLLOW_LINKS)) {
                val history = legacy?.takeIf(Files::isRegularFile)?.let { JSONArray(Files.readString(it)) } ?: JSONArray()
                create(DEFAULT, "默认对话", history)
            }
        } catch (error: Exception) {
            owner.close()
            throw error
        }
    }

    override fun close() = owner.close()

    @Synchronized fun list(): JSONArray = JSONArray(Files.list(sessions).use { paths ->
        paths.filter { valid(it.fileName.toString()) && Files.isDirectory(it, NOFOLLOW_LINKS) }
            .map { path ->
                val metadata = JSONObject(Files.readString(path.resolve("session.json")))
                metadata.put("updated_at", Files.getLastModifiedTime(path.resolve("messages.json")).toMillis())
            }.toList().sortedByDescending { it.getLong("updated_at") }
    })

    @Synchronized fun create(title: String): JSONObject {
        require(title.isNotBlank() && title.length <= 80) { "Invalid session title." }
        check(list().length() < 100) { "Session limit reached." }
        return create(UUID.randomUUID().toString(), title, JSONArray())
    }

    fun exists(id: String): Boolean =
        valid(id) && Files.isDirectory(directory(id), NOFOLLOW_LINKS)

    fun history(id: String): JSONArray =
        JSONArray(Files.readString(directory(id).resolve("messages.json")))

    fun save(id: String, history: JSONArray) {
        atomicWrite(directory(id).resolve("messages.json"), history.toString())
    }

    fun export(id: String): String {
        val history = history(id)
        val markdown = buildString {
            appendLine("# Capri 对话记录")
            for (i in 0 until history.length()) {
                val message = history.getJSONObject(i)
                appendLine()
                appendLine(if (message.getString("role") == "user") "## 用户" else "## Capri")
                appendLine()
                appendLine(message.getString("content"))
            }
        }
        atomicWrite(directory(id).resolve("artifacts/conversation.md"), markdown)
        return markdown
    }

    private fun directory(id: String): Path {
        require(valid(id)) { "Invalid session ID." }
        return sessions.resolve(id)
    }

    private fun create(id: String, title: String, history: JSONArray): JSONObject {
        val stage = Files.createTempDirectory(sessions, ".session-", privateDirectory)
        try {
            val metadata = JSONObject().put("id", id).put("title", title)
            atomicWrite(stage.resolve("session.json"), metadata.toString())
            atomicWrite(stage.resolve("messages.json"), history.toString())
            for (name in listOf("workspace", "artifacts", "run")) Files.createDirectory(stage.resolve(name), privateDirectory)
            Files.move(stage, directory(id), StandardCopyOption.ATOMIC_MOVE)
            return metadata
        } finally {
            if (Files.exists(stage, NOFOLLOW_LINKS)) Files.walk(stage).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }

    companion object {
        const val DEFAULT = "default"
        private val privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

        private fun valid(id: String): Boolean =
            id == DEFAULT || id.length == 36 && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

        private fun atomicWrite(target: Path, value: String) {
            val temporary = Files.createTempFile(target.parent, ".write-", ".tmp", privateFile)
            try {
                Files.writeString(temporary, value)
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
