package ai.capri.app.browser

import ai.capri.app.Workspace
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded crash-safe receipts, not a replay queue. No URL, form value, DOM or cookie is stored. */
internal class BrowserOperations(private val workspace: Workspace) {
    private fun file(chat: String) = AtomicFile(File(workspace.folder(chat,"browser"),"operations.json"))
    private fun load(chat: String): MutableList<JSONObject> {
        val f = file(chat)
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) return mutableListOf()
        val a = JSONArray(f.openRead().bufferedReader().use { it.readText().also { s -> check(s.length <= 65536) } })
        return (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
    }
    private fun save(chat: String, all: List<JSONObject>) {
        val f=file(chat); val out=f.startWrite()
        try { out.write(JSONArray(all.takeLast(64)).toString().toByteArray()); f.finishWrite(out) }
        catch(e:Exception) { f.failWrite(out); throw e }
    }
    @Synchronized fun begin(chat: String, args: JSONObject): JSONObject? {
        val all=load(chat); val id=args.getString("request_id")
        all.firstOrNull { it.getString("request_id")==id }?.let { return JSONObject(it.toString()) }
        all.add(JSONObject().put("request_id",id).put("page_id",args.getString("page_id"))
            .put("action",args.getString("action")).put("action_state","unknown").put("time_ms",System.currentTimeMillis()))
        save(chat,all); return null
    }
    @Synchronized fun finish(chat: String, id: String, state: String) {
        val all=load(chat); all.first { it.getString("request_id")==id }.put("action_state",state)
        save(chat,all)
    }
}
