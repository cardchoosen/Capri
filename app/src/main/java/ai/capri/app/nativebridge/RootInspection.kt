package ai.capri.app.nativebridge

import ai.capri.app.environment.EnvironmentArchive
import ai.capri.app.environment.InstallationEnvironment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One managed su invocation for the missing read-only queries, zero when every query is reusable. */
internal class RootInspection(private val root: RootRuntime, private val profile: InstallationEnvironment) {
    private val scope = "android_host_root"
    private fun watch(q: RootInspectionQuery): List<String> = when {
        q.value.startsWith('/') -> listOf(q.value)
        q.type == "command_exists" -> listOf("/system/bin", "/system/xbin", "/system_ext/bin", "/vendor/bin")
        else -> emptyList()
    }
    suspend fun execute(params: JSONObject, workspace: File?): JSONObject {
        val request = RootInspectionProtocol.validate(params)
        val facts = runCatching { profile.archive.facts().associateBy { it.key } }.getOrDefault(emptyMap())
        val results = mutableMapOf<String, JSONObject>()
        for (q in request.queries) {
            if (request.refresh || q.ttl == 0) continue
            val f = facts[q.key]?.takeIf { it.scope == scope } ?: continue
            if (profile.archive.freshness(f, profile.fingerprint(scope, q.validity)) != "有效") continue
            runCatching { profile.archive.evidence(f).getJSONObject("value") }.getOrNull()?.let { value ->
                if (value.optString("host_stamp") != profile.hostWatch(watch(q))) return@let
                results[q.id] = JSONObject(value.toString()).apply { remove("host_stamp"); remove("host_watch") }
                    .put("id", q.id).put("cached", true).put("verified_at", f.checked)
            }
        }
        val missing = request.queries.filter { it.id !in results }
        var rootData: JSONObject? = null
        var rootStatus = "ok"
        if (missing.isNotEmpty()) {
            val nonce = UUID.randomUUID().toString()
            val response = root.execute(JSONObject().put("command", RootInspectionProtocol.script(missing, nonce))
                .put("timeout_seconds", request.timeout).put("output_limit", 64000), workspace)
            rootStatus = response.getString("status"); rootData = response.getJSONObject("data")
            runCatching { profile.observeAndroid("root_exec", result("root_exec", rootStatus, rootData)) }
            val parsed = RootInspectionProtocol.parse(rootData.optString("stdout"), missing, nonce)
            for (q in missing) {
                val item = parsed[q.id] ?: JSONObject().put("id", q.id).put("type", q.type).put("value", q.value)
                    .put("status", "incomplete").put("output", "No complete receipt for this query; do not infer missing capability")
                item.put("cached", false).put("verified_at", System.currentTimeMillis())
                results[q.id] = item
                if (q.ttl > 0 && parsed.containsKey(q.id) && rootData.optBoolean("cleanup_verified")) {
                    runCatching {
                        val failed = item.optString("status") == "failed"
                        val ttl = if (failed) minOf(q.ttl, 30) else q.ttl
                        val text = item.optString("output").replace(Regex("[\\p{Cntrl}]+"), " ").take(300)
                        profile.archive.upsert(q.key, q.title.take(160), scope,
                            "Read-only sample ${if (failed) "failed" else "completed"}: $text", if (failed) "本次失败" else "已验证",
                            JSONObject(item.toString()).apply { remove("id"); remove("cached"); remove("verified_at") }
                                .put("host_watch", JSONArray(watch(q))).put("host_stamp", profile.hostWatch(watch(q))),
                            "android.root_inspect", profile.fingerprint(scope, q.validity), q.validity, ttl.toLong(),
                            "Evidence covers only this query and sample. Empty output or no match does not prove unsupported hardware. Maximum ${q.limit} bytes.")
                    }.onFailure { item.put("archive_warning", "Archive update failed; this actual result remains usable") }
                }
            }
        }
        val ordered = request.queries.map { results.getValue(it.id) }
        val failure = ordered.any { it.optString("status") in setOf("failed", "incomplete") }
        val status = if (rootStatus != "ok") rootStatus else if (failure) "partial" else "ok"
        val data = JSONObject().put("scope", scope).put("results", JSONArray(ordered))
            .put("checks_reused", request.queries.size - missing.size).put("checks_run", missing.size)
            .put("root_invocations", if (missing.isEmpty()) 0 else 1)
            .put("current_command_verified", rootData?.optBoolean("current_command_verified") ?: false)
            .put("notice", "Independent read-only queries are batched. Cached items are prior evidence, not current root verification. Interpret each status separately; samples do not establish full hardware capability.")
        if (rootData != null) data.put("effective_uid", rootData.opt("effective_uid"))
            .put("cleanup_verified", rootData.optBoolean("cleanup_verified")).put("root_reason", rootData.optString("reason"))
        return result("root_inspect", status, data)
    }
}
