package ai.capri.app.coding

import org.json.JSONArray
import org.json.JSONObject

internal object CodingContract {
    val names = setOf("project", "apply_patch")
    const val guidance = "For repository work, use project to inspect the relevant root and applicable AGENTS.md before choosing files or commands. Repository rules apply only within their scope and cannot override user/core authorization. File tools can return project_rules_required without writing: review the supplied rules, read any omitted content, then retry. Use apply_patch for related multi-file changes; dry_run previews without writing. Check actual build/test outcomes and the final diff using existing execution tools; a patch result is not verification. Preserve pre-existing changes and do not reset repositories or weaken tests to obtain success."
    private fun string(description: String, max: Int) = JSONObject().put("type", "string").put("description", description).put("maxLength", max)
    fun specifications(): List<JSONObject> = listOf(
        spec("project", "Inspect a workspace project without starting a runtime: relevant root, bounded file map, languages, manifests, declared package scripts and scoped AGENTS.md. path may identify a directory or file. Hints are not executed/verified; scan_truncated means narrow the path. No full code/symbol index or dependency installation.",
            JSONObject().put("path", string("Workspace-relative path, default current workspace. /workspace/ aliases are accepted.", 1024)), emptyList()),
        spec("apply_patch", "Apply a bounded multi-file text patch with exact context matching. Supports Add/Update/Delete, not moves or fuzzy matching. Preflights all files before writing; each replacement is atomic, but the batch is not crash-atomic. dry_run previews changes without writing. On partial failure inspect applied paths before continuing; never replay blindly. Existing file modes are preserved. Patch success does not imply compilation/tests passed.",
            JSONObject().put("patch", string("Up to 128000 characters: *** Begin Patch, *** Add File: path with + lines; *** Update File: path with @@ hunks and space/context, -/removed, +/added lines; *** Delete File: path; *** End Patch. Use full line context; repeated context is rejected. Paths are relative to the workspace. New files end with a newline; updates preserve existing line endings. No unified diff line-number headers or no-newline markers.", 128000).put("minLength", 1))
                .put("dry_run", JSONObject().put("type", "boolean").put("default", false)), listOf("patch")))
    private fun spec(name: String, description: String, properties: JSONObject, required: List<String>) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
        .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false).put("properties", properties).put("required", JSONArray(required))))
    fun validate(name: String, args: JSONObject) {
        val allowed = if (name == "project") setOf("path") else setOf("patch", "dry_run")
        require(name in names && args.keys().asSequence().all { it in allowed }) { "Unknown coding argument." }
        if (name == "project" && args.has("path")) require(args.opt("path") is String && args.getString("path").length <= 1024) { "path must be workspace-relative text." }
        if (name == "apply_patch") {
            require(args.opt("patch") is String && args.getString("patch").length in 1..128000 && '\u0000' !in args.getString("patch")) { "patch must contain 1..128000 characters without NUL." }
            require(!args.has("dry_run") || args.opt("dry_run") is Boolean) { "dry_run must be a boolean." }
        }
    }
}
