package ai.capri.app.agent

import org.json.JSONObject

object ResultSignals {
    fun unsuccessful(text: String): Boolean {
        if (text.startsWith("exit=")) return !text.startsWith("exit=0\n") && text != "exit=0"
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return text.startsWith("工具失败")
        fun bad(obj: JSONObject, depth: Int): Boolean {
            if (obj.optBoolean("has_unmerged_proposals") || obj.optBoolean("result_incomplete") || obj.optBoolean("isError") || obj.optString("outcome") == "unknown") return true
            if (obj.optString("status") in setOf("tool_error", "mcp_call_failed", "unavailable", "not_found", "error", "partial", "partial_failure", "project_rules_required", "timeout", "failed", "permission_required", "permission_denied", "execution_failed", "preparation_failed", "invalid_argument", "cancelled", "not_executed", "awaiting_user") || obj.optString("state") in setOf("starting", "running", "pending", "failed", "cancelled", "timeout", "interrupted", "cancel_requested", "launch_failed", "output_limit")) return true
            if (obj.has("success") && obj.opt("success") == false || obj.has("exit_code") && !obj.isNull("exit_code") && obj.optInt("exit_code", -1) != 0 || obj.has("error") && !obj.isNull("error")) return true
            return depth < 3 && listOf("data", "command", "result", "process").any { obj.optJSONObject(it)?.let { child -> bad(child, depth + 1) } == true }
        }
        return bad(root, 0)
    }
}
