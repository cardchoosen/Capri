package ai.capri.app.nativebridge

import ai.capri.app.environment.EnvironmentArchive
import org.json.JSONObject

internal data class RootInspectionQuery(val id: String, val type: String, val value: String, val filter: String,
    val limit: Int, val ttl: Int, val validity: String) {
    val key get() = "probe.host." + EnvironmentArchive.hash("v1|$type|$value|$filter|$limit|$ttl|$validity")
    val title get() = "$type $value".trim()
}
internal data class RootInspectionRequest(val queries: List<RootInspectionQuery>, val refresh: Boolean, val timeout: Int)

/** Small read-only primitives, not arbitrary scripts labelled read-only by the model. */
internal object RootInspectionProtocol {
    private val types = setOf("read_file", "list_directory", "readlink", "command_exists", "getprop", "uname", "kernel_log")
    fun validate(params: JSONObject): RootInspectionRequest {
        require(params.keys().asSequence().all { it in setOf("queries", "refresh", "timeout_seconds") }) { "Unknown batch inspection parameter" }
        val array = params.optJSONArray("queries") ?: throw IllegalArgumentException("queries must be an array")
        require(array.length() in 1..12) { "Expected 1..12 queries" }
        val queries = (0 until array.length()).map { index ->
            val q = array.optJSONObject(index) ?: throw IllegalArgumentException("Each query must be an object")
            require(q.keys().asSequence().all { it in setOf("id", "type", "value", "contains", "limit_bytes", "ttl_seconds", "validity") }) { "Commands and unknown query parameters are not accepted" }
            val id = q.requiredText("id", 40); require(id.matches(Regex("[a-zA-Z0-9_-]+")))
            val type = q.requiredText("type", 40); require(type in types) { "Unknown read-only query type" }
            val value = if (type in setOf("uname", "kernel_log")) {
                require(!q.has("value")) { "${type} does not accept value" }; ""
            } else q.requiredText("value", 512)
            require(value.none { it.isISOControl() })
            when (type) {
                "read_file", "list_directory", "readlink" -> {
                    require(value.startsWith('/') && ".." !in value.split('/'))
                    require(listOf("/sys/", "/proc/", "/system/", "/system_ext/", "/vendor/", "/odm/", "/product/", "/apex/").any(value::startsWith)) { "Use absolute host paths under /sys/, /proc/, /system/, /system_ext/, /vendor/, /odm/, /product/ or /apex/. Resolve tool paths with command_exists; PRoot /bin and /root are not host paths." }
                    require(!Regex("^/proc/(kcore|kmsg|sysrq-trigger|(?:[0-9]+|self|thread-self)/(?:mem|pagemap))(?:/|$)").containsMatchIn(value)) { "Process/kernel memory and special control interfaces cannot be sampled" }
                }
                "command_exists" -> require(value.matches(Regex("[a-zA-Z0-9_][a-zA-Z0-9_+.-]*")) || value.startsWith('/') && ".." !in value.split('/')) { "Only resolve command paths; never execute the program" }
                "getprop" -> {
                    require(value.matches(Regex("[a-zA-Z0-9_.-]+")))
                    require(!Regex("(?i)(password|secret|token|serial|imei|subscriber)").containsMatchIn(value)) { "Environment inspection excludes credentials and personal identifiers" }
                }
            }
            val filter = if (q.has("contains")) q.requiredText("contains", 100) else ""
            require(filter.none { it.isISOControl() })
            require(filter.isEmpty() || type in setOf("kernel_log", "read_file", "list_directory"))
            val dynamic = type == "kernel_log" || value.startsWith("/proc/") || value.startsWith("/sys/class/net/") || type == "getprop" && !value.startsWith("ro.")
            val ttl = q.boundedInt("ttl_seconds", if (type == "kernel_log") 0 else if (dynamic) 5 else 600, 0, if (dynamic) 30 else 86400)
            val validity = if (dynamic) "volatile" else q.optString("validity", "boot")
            require(validity in setOf("boot", "stable", "environment", "volatile"))
            RootInspectionQuery(id, type, value, filter, q.boundedInt("limit_bytes", 1000, 100, 1800), ttl, validity)
        }
        require(queries.map { it.id }.distinct().size == queries.size) { "Query IDs must be unique" }
        return RootInspectionRequest(queries, if (params.has("refresh")) params.boolean("refresh") else false,
            params.boundedInt("timeout_seconds", 15, 1, 40))
    }
    fun script(queries: List<RootInspectionQuery>, nonce: String): String {
        require(nonce.matches(Regex("[0-9a-f-]{36}")))
        return "set -o pipefail\n" + queries.joinToString("\n", postfix = "\nexit 0\n") { q ->
            val value = RootProtocol.quote(q.value)
            val command = when (q.type) {
                "read_file" -> "[ -f $value ] || exit 1; /system/bin/head -c ${q.limit} $value"
                "list_directory" -> "[ -d $value ] || exit 1; /system/bin/ls -1 $value | /system/bin/head -c ${q.limit}"
                "readlink" -> "/system/bin/readlink $value"
                "command_exists" -> "command -v $value"
                "getprop" -> "/system/bin/getprop $value"
                "uname" -> "/system/bin/uname -a"
                "kernel_log" -> "/system/bin/dmesg | /system/bin/tail -n 120"
                else -> error("Unimplemented read-only query")
            }
            val body = if (q.filter.isEmpty()) "( $command ) 2>&1" else "( $command ) 2>&1 | /system/bin/grep -F -e ${RootProtocol.quote(q.filter)}"
            // A bounded stream is a sample, not proof that an entire file/log was read.
            "printf '\\n${nonce}_BEGIN_${q.id}\\n'\n( $body ) | /system/bin/head -c ${q.limit}\n" +
                "capri_probe_code=${'$'}?\nprintf '\\n${nonce}_END_${q.id}_%s\\n' \"${'$'}capri_probe_code\""
        }
    }
    fun parse(output: String, queries: List<RootInspectionQuery>, nonce: String): Map<String, JSONObject> = buildMap {
        for (q in queries) {
            val begin = "\n${nonce}_BEGIN_${q.id}\n"
            val first = output.indexOf(begin); if (first < 0) continue
            val start = first + begin.length
            val end = Regex("\n${Regex.escape(nonce)}_END_${Regex.escape(q.id)}_([0-9]+)\n").find(output, start) ?: continue
            val text = output.substring(start, end.range.first)
            val code = end.groupValues[1].toInt()
            val status = when { code == 0 -> if (text.isBlank()) "empty" else "ok"; code == 141 && text.toByteArray().size >= q.limit -> "sampled"; else -> "failed" }
            put(q.id, JSONObject().put("id", q.id).put("type", q.type).put("value", q.value)
                .put("output", text).put("exit_code", code).put("status", status).put("sample_limit_bytes", q.limit))
        }
    }
}
