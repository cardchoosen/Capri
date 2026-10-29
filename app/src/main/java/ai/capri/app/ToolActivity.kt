package ai.capri.app

import org.json.JSONObject

internal data class ToolActivity(val id: String, val label: String)

internal fun toolLabel(name: String, args: JSONObject?): String = when (name) {
    "project" -> "Inspect project"
    "apply_patch" -> "Apply code patch"
    "profile_search" -> "Search core profile"
    "model_info" -> "View model"
    "web_search" -> "Search web"
    "web_read" -> "Read web page"
    "knowledge" -> "Manage knowledge"
    "context_history" -> "Read current chat history"
    "conversation_history" -> "Read all chat histories"
    "personal_memory" -> "Recall personal memories"
    "android" -> args?.optString("action")?.let { ai.capri.app.nativebridge.NativeCatalog.find(it)?.title } ?: "Android tools"
    "terminal" -> when (args?.optString("action")) {
        "open" -> "Open terminal"; "list", "attach" -> "Inspect terminals"; "run" -> "Run command"; "read" -> "Read terminal output"
        "input" -> "Send terminal input"; "cancel" -> "Cancel command"; "close" -> "Close terminal"
        "clear_history", "delete" -> "Clear terminal history"; else -> "Terminal"
    }
    "browser" -> when (args?.optString("action")) { "open" -> "Open web page"; "observe" -> "Observe web page"; "show" -> "Inspect web page"; "close" -> "Close web page"; else -> "Browser" }
    "python_env" -> when (args?.optString("action")) {
        "install" -> "Install Python packages"; "uninstall" -> "Uninstall Python packages"; "list", "freeze" -> "List Python packages"
        "check" -> "Check Python dependencies"; else -> "Inspect Python environment"
    }
    "environment", "environment_check" -> when (args?.optString("action")) {
        "prepare" -> "Prepare environment"; "verify" -> "Verify environment"; "refresh" -> "Refresh environment checks"
        "lookup" -> "Search environment facts"; "document" -> "Read environment archive"; "record" -> "Record environment observation"; else -> "Inspect environment"
    }
    "extensions" -> when (args?.optString("action")) { "install_skill" -> "Install Skill"; "install_mcp" -> "Install MCP"; "load_skill" -> "Load Skill"; "activate" -> "Connect MCP"; "deactivate" -> "Disconnect MCP"; "read_resource" -> "Read Skill resource"; else -> "Find extensions" }
    "python_run" -> "Run short task"; "list_files" -> "List files"; "read_file" -> "Read file"
    "search_files" -> "Search file contents"; "edit_file" -> "Edit file"
    "write_file" -> "Write file"; "send_file" -> "Send attachment"; else -> name
}
internal fun toolRecordLabel(entry: Entry): String = toolLabel(entry.text.substringBefore('\n'),
    runCatching { JSONObject(entry.text.substringAfter('\n')) }.getOrNull())
