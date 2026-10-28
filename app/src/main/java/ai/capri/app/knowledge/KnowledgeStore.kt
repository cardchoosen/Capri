package ai.capri.app.knowledge

import android.util.AtomicFile
import ai.capri.app.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

/** Explicit, sourced knowledge. A snapshot index, never an editor for persona or workspace files. */
internal class KnowledgeStore(
    private val root: File, private val workspace: Workspace,
    private val readText: (File) -> String = { AtomicFile(it).openRead().bufferedReader().use { reader -> reader.readText() } },
    private val writeText: (File, String) -> Unit = { path, text ->
        val target = AtomicFile(path); val stream = target.startWrite()
        try { stream.write(text.toByteArray()); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
) {
    private val file get() = File(root.apply { mkdirs() }, "knowledge.json")
    @Synchronized private fun load(): JSONArray = try {
        JSONArray(readText(file))
    } catch (_: java.io.FileNotFoundException) { JSONArray() }
    private fun save(data: JSONArray) = writeText(file, data.toString())
    // Personal records share the same atomic store and index but never appear in the generic editing panel.
    @Synchronized internal fun personalRecords(chat: String): List<JSONObject> = load().let { data ->
        (0 until data.length()).map { data.getJSONObject(it) }.filter {
            it.optString("scope") == chat && it.optString("kind") in setOf("personal_note", "episode")
        }
    }
    @Synchronized internal fun savePersonal(record: JSONObject) {
        require(record.getString("kind") in setOf("personal_note", "episode"))
        val data = load()
        val index = (0 until data.length()).firstOrNull { data.getJSONObject(it).getString("id") == record.getString("id") }
        if (index == null) data.put(record) else {
            require(data.getJSONObject(index).optString("kind") == record.getString("kind") &&
                data.getJSONObject(index).optString("scope") == record.getString("scope"))
            data.put(index, record)
        }
        // Bound the recall index; original messages remain searchable through conversation_history.
        val episodes = (0 until data.length()).filter { data.getJSONObject(it).optString("kind") == "episode" }
            .sortedByDescending { data.getJSONObject(it).optLong("updated") }.drop(1000).toSet()
        save(JSONArray((0 until data.length()).filterNot { it in episodes }.map { data.getJSONObject(it) }))
    }
    private fun visible(record: JSONObject, chat: String) = record.getString("scope") == "global" || record.getString("scope") == chat
    @Synchronized fun records(chat: String): List<JSONObject> = load().let { data ->
        (0 until data.length()).map { data.getJSONObject(it) }.filter { visible(it, chat) && it.optString("kind") in setOf("memory", "document") }
    }
    @Synchronized fun putMemory(chat: String, text: String, source: String, global: Boolean = false, id: String = ""): JSONObject {
        require(text.isNotBlank() && text.length <= 4000) { "记忆须为1–4000字符" }
        require(source.isNotBlank() && source.length <= 500) { "记忆必须附带来源" }
        require(!Regex("(?i)(sk-[a-z0-9]{16,}|-----BEGIN .*PRIVATE KEY|bearer\\s+[a-z0-9._-]{16,})").containsMatchIn(text)) { "请勿把密钥写入记忆" }
        val data = load()
        val existing = if (id.isBlank()) null else (0 until data.length()).map { data.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
        if (id.isNotBlank()) require(existing != null && visible(existing, chat) && existing.getString("kind") == "memory") { "记忆不存在或不属于可管理范围" }
        val record = JSONObject().put("id", existing?.getString("id") ?: UUID.randomUUID().toString()).put("kind", "memory")
            .put("scope", if (global) "global" else chat).put("origin_chat", chat).put("title", text.take(60)).put("text", text)
            .put("source", source).put("enabled", existing?.optBoolean("enabled", true) ?: true).put("updated", System.currentTimeMillis())
        if (existing == null) { require((0 until data.length()).count { data.getJSONObject(it).optString("kind") in setOf("memory", "document") } < 300) { "知识条目上限300，请先清理" }; data.put(record) }
        else for (i in 0 until data.length()) if (data.getJSONObject(i).getString("id") == id) data.put(i, record)
        save(data); return record
    }
    @Synchronized fun index(chat: String, path: String, global: Boolean = false): JSONObject {
        val source = workspace.file(chat, path.removePrefix("/workspace/"))
        require(source.extension.lowercase() in setOf("txt", "md", "markdown", "csv", "json", "jsonl", "log", "yaml", "yml", "xml", "html", "htm")) { "仅支持 UTF-8 文本/Markdown/CSV/JSON/JSONL/日志/YAML/XML/HTML；PDF、Office及图片暂不支持" }
        require(source.isFile && source.length() <= 2_000_000) { "文件不存在或超过2MB" }
        val bytes = source.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 2_000_000) { "文件超过2MB" }; output.write(buffer, 0, count) }
            output.toByteArray()
        }
        require(bytes.size <= 2_000_000) { "文件超过2MB" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require('\u0000' !in text && text.isNotBlank()) { "不是有效文本或文件为空" }
        val chunks = TextIndex.chunks(text)
        require(chunks.size <= 1500) { "文件段落过多，请拆分后索引" }
        val data = load()
        val scope = if (global) "global" else chat
        val existing = (0 until data.length()).map { data.getJSONObject(it) }.firstOrNull { it.optString("kind") == "document" && it.optString("origin_chat") == chat && it.optString("path") == path && it.optString("scope") == scope }
        val total = (0 until data.length()).sumOf { data.getJSONObject(it).optJSONArray("chunks")?.length() ?: 0 } - (existing?.optJSONArray("chunks")?.length() ?: 0)
        require(total + chunks.size <= 5000) { "索引总量上限5000段，请先清理资料" }
        val record = JSONObject().put("id", existing?.getString("id") ?: UUID.randomUUID().toString()).put("kind", "document").put("scope", scope)
            .put("origin_chat", chat).put("title", source.name).put("path", path).put("source", "工作区快照：$path")
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            .put("chunks", JSONArray(chunks)).put("enabled", existing?.optBoolean("enabled", true) ?: true).put("updated", System.currentTimeMillis())
        if (existing == null) { require((0 until data.length()).count { data.getJSONObject(it).optString("kind") in setOf("memory", "document") } < 300); data.put(record) }
        else for (i in 0 until data.length()) if (data.getJSONObject(i).getString("id") == record.getString("id")) data.put(i, record)
        save(data); return record
    }
    @Synchronized fun deleteChat(chat: String) {
        val data = load()
        save(JSONArray((0 until data.length()).map { data.getJSONObject(it) }.filterNot { it.optString("scope") == chat }))
    }
    @Synchronized fun change(chat: String, id: String, enabled: Boolean? = null) {
        val data = load(); val index = (0 until data.length()).firstOrNull { data.getJSONObject(it).getString("id") == id && visible(data.getJSONObject(it), chat) && data.getJSONObject(it).optString("kind") in setOf("memory", "document") }
        require(index != null) { "条目不存在或不属于可管理范围" }
        if (enabled == null) data.remove(index) else data.getJSONObject(index).put("enabled", enabled)
        save(data)
    }
    @Synchronized fun search(chat: String, query: String): JSONArray {
        require(query.isNotBlank() && query.length <= 300) { "查询须为1–300字符" }
        val hits = mutableListOf<Pair<Int, JSONObject>>()
        records(chat).filter { it.optBoolean("enabled") }.forEach { record ->
            val chunks = if (record.getString("kind") == "memory") listOf(JSONObject().put("text", record.getString("text")).put("start", 1).put("end", 1))
                else record.getJSONArray("chunks").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            chunks.forEachIndexed { index, chunk ->
                val score = TextIndex.score(query, record.getString("title") + "\n" + chunk.getString("text"))
                if (score > 0) hits += score to JSONObject().put("citation", "${record.getString("id")}:${index + 1}")
                    .put("id", record.getString("id")).put("kind", record.getString("kind")).put("scope", if (record.getString("scope") == "global") "global" else "chat")
                    .put("source", record.getString("source")).put("sha256", record.optString("sha256")).put("updated", record.getLong("updated"))
                    .put("line_start", chunk.getInt("start")).put("line_end", chunk.getInt("end")).put("text", chunk.getString("text"))
            }
        }
        return JSONArray(hits.sortedByDescending { it.first }.take(6).map { it.second })
    }
}

internal object TextIndex {
    fun chunks(text: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>(); var body = StringBuilder(); var start = 1; var end = 1
        fun flush() { if (body.isNotEmpty()) result += JSONObject().put("start", start).put("end", end).put("text", body.toString()); body = StringBuilder() }
        text.lineSequence().forEachIndexed { index, line ->
            // Split oversized lines too; citations retain their original line number.
            line.chunked(1000).ifEmpty { listOf("") }.forEach { part ->
                if (body.length + part.length + 1 > 1400) flush()
                if (body.isEmpty()) start = index + 1
                end = index + 1; body.append(part).append('\n')
            }
            if (line.isBlank() && body.length >= 300) flush()
        }
        flush(); return result
    }
    fun score(query: String, text: String): Int {
        val q = query.trim().lowercase(); val hay = text.lowercase()
        val words = Regex("[a-z0-9_-]+|[\\p{IsHan}]+").findAll(q).flatMap { match ->
            if (match.value.any { it.code > 127 } && match.value.length > 1) match.value.windowed(2).asSequence() else sequenceOf(match.value)
        }.toSet()
        return (if (hay.contains(q)) 20 else 0) + words.count { hay.contains(it) } * 2
    }
}
