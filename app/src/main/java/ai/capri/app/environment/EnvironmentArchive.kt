package ai.capri.app.environment

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal data class EnvironmentFact(
    val key: String, val title: String, val scope: String, val conclusion: String, val status: String,
    val evidence: String, val source: String, val checked: Long, val ttl: Long,
    val fingerprint: String, val validity: String, val conditions: String, val invalidated: String = ""
) {
    val id get() = EnvironmentArchive.hash("$scope\n$key")
}

/** environment.md is the canonical record, not a view of a second mutable JSON database. */
internal class EnvironmentArchive(
    val root: File,
    private val read: (File) -> String = { AtomicFile(it).openRead().bufferedReader().use { reader -> reader.readText() } },
    private val write: (File, String) -> Unit = { file, text ->
        file.parentFile!!.mkdirs(); val target = AtomicFile(file); val out = target.startWrite()
        try { out.write(text.toByteArray()); target.finishWrite(out) } catch (e: Exception) { target.failWrite(out); throw e }
    },
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        private const val HEADER = "# Capri 安装环境档案\n\n<!-- capri-environment-v1 -->\n\n按环境保存有来源的事实与观察。历史成功不代替当前权限；条目过期或条件变化时只补测相关内容。\n正文是当前档案，完整依据见 evidence/；旧版见 history/。不保存账号、凭据和任务目标数据。\n"
        fun safe(value: String, max: Int): String {
            require(value.length <= max && value.none { it.isISOControl() }) { "Archive fields must be bounded text without control characters." }
            require(!Regex("(?i)(sk-[a-z0-9_-]{16,}|ghp_[a-z0-9]+|github_pat_|-----BEGIN .*PRIVATE KEY|bearer\\s+[a-z0-9._-]{12,}|(?:password|密码|api[_ -]?key)\\s*[:=])").containsMatchIn(value)) { "Credentials cannot be stored in the environment archive." }
            return value
        }
    }
    val document get() = File(root, "environment.md")
    private fun load(): List<EnvironmentFact> {
        val text = try { read(document) } catch (_: java.io.FileNotFoundException) { return emptyList() }
        require(text.contains("<!-- capri-environment-v1 -->")) { "Archive format is damaged; original text was not overwritten." }
        val entries = text.split("\n## ").drop(1).map { block ->
            val fields = block.lineSequence().drop(1).filter { it.startsWith("- ") && "：" in it }.associate {
                it.removePrefix("- ").substringBefore('：') to it.substringAfter('：')
            }
            fun field(key: String) = fields[key] ?: error("Archive is missing $key; original text was not overwritten.")
            val time = field("验证").split(" | "); require(time.size == 4)
            val proof = field("证据").split(" | ", limit = 2); require(proof.size == 2)
            EnvironmentFact(field("标识"), block.substringBefore('\n'), field("环境"), field("结论"), field("状态"),
                proof[0], proof[1], Instant.parse(time[0]).toEpochMilli(), time[2].toLong(),
                time[3], time[1], field("适用条件"), field("失效原因"))
        }
        require(entries.map { it.id }.distinct().size == entries.size) { "Archive contains duplicate entries; original text was not overwritten." }
        return entries
    }
    private fun markdown(facts: List<EnvironmentFact>): String = HEADER + facts.sortedWith(compareBy<EnvironmentFact> { it.scope }.thenBy { it.key }).joinToString("") { f ->
        "\n## ${f.title}\n" + listOf("标识" to f.key, "环境" to f.scope, "状态" to f.status, "结论" to f.conclusion,
            "适用条件" to f.conditions, "验证" to "${Instant.ofEpochMilli(f.checked)} | ${f.validity} | ${f.ttl} | ${f.fingerprint}",
            "失效原因" to f.invalidated, "证据" to "${f.evidence} | ${f.source}").joinToString("\n", postfix = "\n") { "- ${it.first}：${it.second}" }
    }
    private fun save(facts: List<EnvironmentFact>) {
        val text = markdown(facts)
        val old = try { read(document) } catch (_: java.io.FileNotFoundException) { "" }
        if (old == text) return
        if (old.isNotBlank()) {
            write(File(root, "history/${now()}-${UUID.randomUUID()}.md"), old)
            File(root, "history").listFiles().orEmpty().sortedByDescending { it.name }.drop(20).forEach { it.delete() }
        }
        write(document, text)
    }
    @Synchronized fun facts(): List<EnvironmentFact> = load()
    @Synchronized fun upsert(key: String, title: String, scope: String, conclusion: String, status: String,
                             value: JSONObject, source: String, fingerprint: String, validity: String = "environment",
                             ttl: Long = 86400, conditions: String = "仅适用于记录的执行环境") : EnvironmentFact {
        safe(key, 320); safe(title, 160); safe(scope, 160); safe(conclusion, 1600); safe(source, 300); safe(conditions, 600)
        require(key.isNotBlank() && scope.isNotBlank() && title.isNotBlank())
        require(status in setOf("已验证", "本次失败", "观察", "未知"))
        require(validity in setOf("stable", "boot", "environment", "volatile") && ttl in 1..2592000)
        val all = load().toMutableList(); val id = hash("$scope\n$key"); val previous = all.firstOrNull { it.id == id }
        if (previous != null && previous.conclusion == conclusion && previous.status == status && previous.fingerprint == fingerprint &&
            previous.invalidated.isEmpty() && previous.validity == validity && previous.ttl == ttl && previous.conditions == conditions &&
            now() >= previous.checked && now() - previous.checked < previous.ttl * 1000 && evidence(previous).optJSONObject("value")?.toString() == value.toString()) return previous
        val path = "evidence/${UUID.randomUUID()}.json"
        write(File(root, path), JSONObject().put("source", source).put("scope", scope).put("at", now()).put("value", value).toString())
        val fact = EnvironmentFact(key, title, scope, conclusion, status, path, source, now(), ttl, fingerprint, validity, conditions)
        all.removeAll { it.id == id }; all.add(fact); save(all); return fact
    }
    @Synchronized fun invalidate(predicate: (EnvironmentFact) -> Boolean, reason: String) {
        safe(reason, 300)
        save(load().map { if (predicate(it) && it.invalidated.isBlank()) it.copy(invalidated = reason) else it })
    }
    fun freshness(fact: EnvironmentFact, currentFingerprint: String): String = when {
        fact.invalidated.isNotBlank() -> fact.invalidated
        now() < fact.checked -> "系统时间回退，待复核"
        now() - fact.checked >= fact.ttl * 1000 -> "有效期已过"
        fact.fingerprint != currentFingerprint -> "环境条件已变化"
        else -> "有效"
    }
    @Synchronized fun evidence(fact: EnvironmentFact): JSONObject {
        require(fact.evidence.matches(Regex("evidence/[0-9a-f-]{36}\\.json")))
        return JSONObject(read(File(root, fact.evidence)))
    }
    @Synchronized fun view(query: String = "", scope: String = "", offset: Int = 0): JSONObject {
        require(query.length <= 300 && offset >= 0)
        val all = load().filter { (scope.isBlank() || it.scope == scope) && (query.isBlank() || (it.key + it.title + it.conclusion + it.conditions).contains(query, true)) }
        val text = markdown(all)
        val page = ai.capri.app.ExecutionResult.textPage(text, offset)
        val next = minOf(offset, text.length) + page.length
        return JSONObject().put("path", document.path).put("text", page).put("total_chars", text.length)
            .put("next_offset", if (next < text.length) next else JSONObject.NULL).put("entries", all.size)
    }
    @Synchronized fun initialize() {
        if (document.exists() || File(root, "environment.md.bak").exists()) load() else save(emptyList())
    }
}
