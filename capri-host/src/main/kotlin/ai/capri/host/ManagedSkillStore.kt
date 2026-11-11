package ai.capri.host

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal class ManagedSkillStore(workspace: Path) {
    private val directory = workspace.resolve(".capri-skill-catalog")

    fun active(name: String): JSONObject? {
        val state = state(name)
        val active = state.optString("active")
        return versions(state).firstOrNull { it.getString("revision") == active }
    }

    fun names(): List<String> {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return emptyList()
        return Files.list(directory).use { entries ->
            entries.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".json") }
                .map { it.fileName.toString().removeSuffix(".json") }.filter { it.matches(identifier) && active(it) != null }
                .limit(101).toList()
        }
    }

    fun retained(name: String): List<String> {
        val state = state(name)
        return versions(state).map { it.getString("revision") }.filter { it != state.optString("active") }
    }

    fun install(name: String, bundle: JSONObject) {
        val state = state(name)
        val id = bundle.getString("revision")
        val previous = state.optString("active")
        val entries = versions(state).filterNot { it.getString("revision") == id }.toMutableList()
        entries.add(bundle)
        if (entries.size > 8) entries.removeAt(entries.indexOfFirst { it.getString("revision") !in setOf(id, previous) })
        save(name, state.put("active", id).put("versions", JSONArray(entries)))
    }

    fun activate(name: String, revision: String?): Boolean {
        val state = state(name)
        if (revision != null && versions(state).none { it.getString("revision") == revision }) return false
        state.put("active", revision ?: JSONObject.NULL)
        save(name, state)
        return true
    }

    private fun file(name: String): Path {
        require(name.matches(identifier))
        if (Files.exists(directory, NOFOLLOW_LINKS) && !Files.isDirectory(directory, NOFOLLOW_LINKS))
            throw WorkspaceFailure("skill_store_unavailable", "The installed Skill store is unavailable.")
        return directory.resolve("$name.json")
    }

    private fun state(name: String): JSONObject {
        val path = file(name)
        return if (Files.isRegularFile(path, NOFOLLOW_LINKS)) JSONObject(Files.readString(path)) else JSONObject()
    }

    private fun save(name: String, state: JSONObject) {
        val path = file(name)
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, ".install-", ".tmp")
        try {
            Files.writeString(temp, state.toString())
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun versions(state: JSONObject) = jsonObjects(state.optJSONArray("versions") ?: JSONArray())

    companion object {
        private val identifier = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}
