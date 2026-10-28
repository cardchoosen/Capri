package ai.capri.app

import android.os.Bundle
import android.os.Looper
import ai.capri.app.extensions.ToolsCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only directory views. Disk/config reads run on the socket worker, never connect or execute. */
internal object DebugWebCatalog {
    class Ticket internal constructor(private val readData: () -> JSONObject) {
        fun read(): JSONObject = try {
            readData().also { check(it.toString().length <= 100000) { "Catalog result too large; narrow the query." } }
        } catch (error: Exception) { JSONObject().put("error", error.message ?: "Catalog unavailable.") }
    }
    fun prepare(vm: CapriModel, args: Bundle): Ticket {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(args.getString("epoch") == DebugWebBridge.epoch) { "App restarted; refresh the catalog." }
        val chat = args.getString("chat_id").orEmpty()
        require(vm.sessions.any { it.id == chat } && chat !in vm.deletingChats) { "Unknown or deleting chat." }
        val category = args.getString("category", "tools")
        require(category in setOf("tools", "mcp", "skills")) { "Unknown catalog category." }
        val query = args.getString("query").orEmpty().trim(); require(query.length <= 300)
        val id = args.getString("id").orEmpty(); require(id.length <= 200)
        val offset = args.getInt("offset", 0); require(offset >= 0)
        val manager = vm.extensions
        val builtins = vm.builtinTools()
        return Ticket {
            val rows: List<JSONObject> = when (category) {
                "tools" -> ToolsCatalog.entries(builtins, manager.specifications(chat), { toolLabel(it, null) }, { manager.label(chat, it) }).map {
                    JSONObject().put("id", it.name).put("name", it.title).put("source", it.source).put("mcp", it.mcp)
                        .put("description", it.description).put("parameters", it.parameters)
                }
                "mcp" -> manager.store.configs().map { config ->
                    val key = config.getString("id")
                    // Never serialize raw configurations, commands, URLs, headers, tokens, or credential values.
                    JSONObject().put("id", key).put("name", config.getString("name")).put("description", config.optString("description"))
                        .put("type", config.getString("type")).put("enabled", config.optBoolean("enabled"))
                        .put("active", manager.store.active(chat, key)).put("state", manager.state(chat, key))
                }
                else -> manager.store.skills().map {
                    JSONObject().put("id", it.id).put("name", it.name).put("description", it.description)
                        .put("enabled", manager.store.enabled(it)).put("builtin", it.builtin)
                }
            }
            if (id.isNotBlank()) {
                val item = rows.firstOrNull { it.getString("id") == id } ?: error("Catalog item no longer available.")
                if (category == "skills") manager.store.withSkill(id) { skill ->
                    val file = File(skill.directory, "SKILL.md")
                    require(file.isFile && file.length() <= 128000) { "Skill document unavailable or oversized." }
                    val text = file.readText(); val page = ExecutionResult.textPage(text, offset)
                    val next = minOf(offset, text.length) + page.length
                    item.put("content", page).put("total_chars", text.length).put("offset", offset)
                        .put("next_offset", if (next < text.length) next else JSONObject.NULL)
                }
                JSONObject().put("category", category).put("chat_id", chat).put("item", item)
            } else {
                val matches = rows.filter { query.isBlank() || (it.optString("name") + " " + it.optString("id") + " " + it.optString("description") + " " + it.optString("source")).contains(query, true) }
                val page = matches.drop(offset).take(8).map { JSONObject(it.toString()).apply { remove("parameters"); put("description", optString("description").take(350)) } }
                JSONObject().put("category", category).put("chat_id", chat).put("items", JSONArray(page)).put("total", matches.size)
                    .put("next_offset", if (offset.toLong() + page.size < matches.size) offset + page.size else JSONObject.NULL)
            }
        }
    }
}
