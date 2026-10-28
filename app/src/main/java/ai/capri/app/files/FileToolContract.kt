package ai.capri.app.files

import org.json.JSONArray
import org.json.JSONObject

/** One source for model schemas and runtime argument boundaries. */
internal object FileToolContract {
    const val guidance = "Use list_files/search_files to locate workspace text, read_file for relevant ranges, and edit_file for precise changes. Use write_file for new files or complete replacement. Reuse sha256 to guard edits and paginated reads. File tools need no runtime preparation. Use execution tools for unsupported formats or advanced search. Use send_file to deliver artifacts."
    private data class Field(val schema: JSONObject, val required: Boolean = false)
    private fun text(description: String, max: Int, required: Boolean = false) = Field(JSONObject().put("type", "string").put("description", description).put("maxLength", max), required)
    private fun integer(description: String, min: Int, max: Int, default: Int) = Field(JSONObject().put("type", "integer").put("description", description).put("minimum", min).put("maximum", max).put("default", default))
    private fun flag(description: String, default: Boolean) = Field(JSONObject().put("type", "boolean").put("description", description).put("default", default))
    private fun path(required: Boolean = false) = text("Path relative to the current workspace; /workspace/ is also accepted. Parent traversal and symbolic links are rejected.", 1024, required)
    private fun version() = Field(JSONObject().put("type", "string").put("pattern", "^[0-9a-fA-F]{64}$").put("description", "Optional version from read_file.sha256. A mismatch returns conflict without writing."))
    private fun fields(name: String): Map<String, Field> = when (name) {
        "list_files", "search_files" -> linkedMapOf(
            "path" to path(),
            "pattern" to text("Optional workspace-relative file glob, such as **/*.kt. **/ also matches root-level files.", 256),
            "include_ignored" to flag("Include hidden files and common dependency/build caches; false by default.", false),
            "offset" to integer("Zero-based result offset; continue with next_offset.", 0, Int.MAX_VALUE, 0),
            "limit" to integer("Maximum results in this page; output is additionally bounded.", 1, if (name == "list_files") 200 else 100, if (name == "list_files") 100 else 50)
        ).apply {
            if (name == "search_files") {
                put("query", Field(text("Nonempty literal text on one line; not a regular expression. One match is returned per matching line.", 500, true).schema.put("minLength", 1), true))
                put("case_sensitive", flag("Whether letter case must match.", true))
            }
        }
        "read_file" -> linkedMapOf("path" to path(true),
            "start_line" to integer("First line, counted from 1.", 1, 10000000, 1),
            "start_column" to integer("Unicode code points to skip in the first line; use next_column when continuing a long line.", 0, 10000000, 0),
            "max_lines" to integer("Maximum physical lines to return; long output may end earlier.", 1, 1000, 200),
            "expected_sha256" to version())
        "write_file" -> linkedMapOf("path" to path(true), "content" to text("Complete UTF-8 text, at most 1000000 bytes. Existing files are replaced; prefer edit_file for small changes.", 1000000, true), "expected_sha256" to version())
        "edit_file" -> linkedMapOf("path" to path(true),
            "old_text" to Field(text("Exact, nonempty text to replace. Include enough context for a unique match.", 128000, true).schema.put("minLength", 1), true),
            "new_text" to text("Replacement text. An empty string removes old_text.", 128000, true),
            "replace_all" to flag("Replace all non-overlapping matches; otherwise require one unique match.", false), "expected_sha256" to version())
        else -> error("Unknown workspace tool.")
    }
    fun specifications(): List<JSONObject> = WorkspaceFileTools.names.map { name ->
        val description = when (name) {
            "list_files" -> "List workspace files with optional directory and glob filters. Hidden/dependency directories are skipped by default. Follow next_offset only when has_more; scan_truncated means narrow the scope. No shell or environment setup is needed."
            "read_file" -> "Read valid UTF-8 text in bounded line ranges, up to 8 MiB per source file. Returns content, sha256 and continuation coordinates. If has_more, resume with next_line/next_column and expected_sha256. Binary files require a format-specific reader."
            "write_file" -> "Create or atomically replace a UTF-8 workspace file (up to 1000000 bytes). Pass expected_sha256 when replacing a version you inspected. For an existing file's small change, prefer edit_file. Returns the new version and whether content changed."
            "search_files" -> "Find literal text in workspace UTF-8 files without a terminal. Returns bounded matches with file paths and 1-based line/column numbers. Use pattern/path to narrow scope; binary/oversized files are skipped. For advanced regular expressions, use the terminal."
            else -> "Apply an exact text replacement in a UTF-8 workspace file (result up to 8 MiB). By default old_text must match once; zero or multiple matches return an error without writing. expected_sha256 guards a known version. Writes are atomic and preserve file permissions."
        }
        val fields = fields(name)
        spec(name, description, JSONObject().apply { fields.forEach { (key, field) -> put(key, field.schema) } }, fields.filterValues(Field::required).keys.toList())
    }
    fun sendSpecification() = spec("send_file", "Attach an existing workspace file to this conversation, up to 20 MB. PNG/JPEG/GIF/WebP are displayed inline on phone and web; other files can be downloaded. Use workspace-relative paths or /workspace/. Do not attach credentials or private keys.",
        JSONObject().put("path", path(true).schema).put("caption", text("Optional short caption in the user's language.", 240).schema), listOf("path"))
    private fun spec(name: String, description: String, properties: JSONObject, required: List<String>) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
        .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false).put("properties", properties).put("required", JSONArray(required))))
    fun validate(name: String, args: JSONObject) {
        val fields = fields(name)
        require(args.keys().asSequence().all { it in fields }) { "Unknown workspace argument." }
        for ((key, field) in fields) {
            require(!field.required || args.has(key)) { "Missing required argument: $key." }
            if (!args.has(key)) continue
            val value = args.opt(key); val schema = field.schema
            when (schema.getString("type")) {
                "string" -> {
                    require(value is String) { "$key must be a string." }
                    require(value.length <= schema.optInt("maxLength", Int.MAX_VALUE) && value.length >= schema.optInt("minLength", 0)) { "$key has an invalid length." }
                    if (schema.has("pattern")) require(Regex(schema.getString("pattern")).matches(value)) { "$key has an invalid format." }
                }
                "integer" -> {
                    require(value is Int || value is Long) { "$key must be an integer." }
                    require((value as Number).toLong() in schema.getLong("minimum")..schema.getLong("maximum")) { "$key is outside its supported range." }
                }
                "boolean" -> require(value is Boolean) { "$key must be a boolean." }
            }
        }
        if (name == "search_files") require(args.getString("query").isNotBlank() && '\n' !in args.getString("query") && '\r' !in args.getString("query")) { "query must be nonempty literal text on one line." }
    }
}
