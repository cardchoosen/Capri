package ai.capri.app.retrieval

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal class RetrievalStore(private val root: File) {
    private fun dir(chat: String): File {
        require(chat.matches(Regex("[a-zA-Z0-9-]+")))
        return File(root, chat).apply { mkdirs() }
    }
    private fun write(file: File, value: JSONObject) {
        val af = AtomicFile(file); val stream = af.startWrite()
        try { stream.write(value.toString().toByteArray()); af.finishWrite(stream) } catch (e: Exception) { af.failWrite(stream); throw e }
    }
    private fun read(file: File): JSONObject? = try { JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }) }
        catch (_: java.io.FileNotFoundException) { null }
    @Synchronized fun source(chat: String, data: JSONObject): JSONObject {
        val id = "web-" + UUID.randomUUID().toString()
        val time = System.currentTimeMillis()
        val record = JSONObject(data.toString()).put("source_id", id).put("fetched_at", time).put("fetched_at_iso", java.time.Instant.ofEpochMilli(time).toString())
        write(File(dir(chat), "$id.json"), record)
        val files = dir(chat).listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.lastModified() }.toMutableList()
        var bytes = files.sumOf { it.length() }
        while (files.size > 80 || bytes > 16_000_000) { val oldest = files.removeAt(0); bytes -= oldest.length(); AtomicFile(oldest).delete() }
        return record
    }
    @Synchronized fun get(chat: String, id: String): JSONObject {
        require(id.matches(Regex("web-[0-9a-f-]{36}"))) { "source_id格式无效" }
        return read(File(dir(chat), "$id.json")) ?: throw RetrievalFailure("source_expired_or_not_in_chat")
    }
    @Synchronized fun cachedSearch(chat: String, query: String, fingerprint: String): JSONObject? {
        val value = read(File(dir(chat), "search-cache.json")) ?: return null
        if (value.optString("key") != cacheKey(query, fingerprint) || System.currentTimeMillis() - value.optLong("at") !in 0..120000) return null
        val response = value.getJSONObject("response")
        val results = response.getJSONArray("results")
        if ((0 until results.length()).any { !File(dir(chat), results.getJSONObject(it).getString("source_id") + ".json").isFile }) return null
        return response.put("cached", true)
    }
    @Synchronized fun saveSearch(chat: String, query: String, fingerprint: String, response: JSONObject) {
        write(File(dir(chat), "search-cache.json"), JSONObject().put("key", cacheKey(query, fingerprint)).put("at", System.currentTimeMillis()).put("response", response))
    }
    private fun cacheKey(query: String, fingerprint: String) = MessageDigest.getInstance("SHA-256").digest((fingerprint + "\n" + query).toByteArray()).joinToString("") { "%02x".format(it) }
}
