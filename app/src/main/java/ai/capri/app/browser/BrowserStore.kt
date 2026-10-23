package ai.capri.app.browser

import ai.capri.app.Workspace
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class BrowserRecord(val id: String, var url: String, var title: String = "网页")
/** Only navigation metadata; never cookies, DOM, form values or JS state. */
internal class BrowserStore(private val workspace: Workspace) {
    private fun index(chat: String) = AtomicFile(File(workspace.folder(chat, "browser"), "pages.json"))
    @Synchronized fun load(chat: String): MutableList<BrowserRecord> = runCatching {
        val array = JSONArray(index(chat).openRead().bufferedReader().use { it.readText().take(32768) })
        (0 until array.length().coerceAtMost(5)).map { array.getJSONObject(it) }.map {
            val id = it.getString("id"); require(java.util.UUID.fromString(id).toString() == id)
            BrowserRecord(id, it.getString("url"), it.optString("title", "网页").take(160))
        }.distinctBy { it.id }.toMutableList()
    }.getOrElse { mutableListOf() }
    @Synchronized fun save(chat: String, pages: List<BrowserRecord>) {
        val data = JSONArray().apply { pages.forEach { put(JSONObject().put("id", it.id)
            .put("url", BrowserContract.recoveryUrl(it.url)).put("title", it.title.take(160))) } }
        val file = index(chat); val out = file.startWrite()
        try { out.write(data.toString().toByteArray()); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
}
