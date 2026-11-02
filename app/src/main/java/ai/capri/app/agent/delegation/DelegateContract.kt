package ai.capri.app.agent.delegation

import ai.capri.app.ActionSchema
import ai.capri.app.agent.TaskState
import ai.capri.app.agent.ToolInput
import org.json.JSONArray
import org.json.JSONObject

internal object DelegateContract {
    val parentEvidenceTools=setOf("read_file","list_files","search_files","project","coding_reference","coding_diagnostics","web_search","web_read","python_run","terminal","program_run","agent_delegate")
    val readTools=setOf("list_files","read_file","search_files","project")
    val writeTools=setOf("write_file","edit_file")
    private val fields=mapOf("run" to setOf("request_key","reason","tasks","parallel"),"list" to setOf("offset"),"read" to setOf("group_id","task_id","view","path","offset"),
        "merge" to setOf("group_id","task_id","expected_proposal"),"discard" to setOf("group_id"))
    private fun required(action: String)=when(action) { "run" -> listOf("request_key","reason","tasks");"list" -> emptyList();"read" -> listOf("group_id");else -> fields.getValue(action).toList() }
    private fun text(max: Int,description: String="")=JSONObject().put("type","string").put("minLength",1).put("maxLength",max).put("description",description)
    private fun strings(max: Int,chars: Int)=JSONObject().put("type","array").put("maxItems",max).put("uniqueItems",true).put("items",text(chars))
    private fun schema(): JSONObject {
        val task=JSONObject().put("type","object").put("additionalProperties",false).put("required",JSONArray(listOf("id","role","goal","files")))
            .put("properties",JSONObject().put("id",text(40).put("pattern","^[a-zA-Z0-9_-]+$"))
                .put("role",text(20).put("enum",JSONArray(listOf("investigator","reviewer","coder"))))
                .put("goal",text(2000,"Independent bounded assignment; not the entire conversation."))
                .put("constraints",strings(12,400)).put("evidence",strings(4,200).put("description","Actual parent file/web/execution tool-call IDs; memory, phone, account, Skill and MCP results are excluded. At most 2000 characters per reference are forwarded."))
                .put("files",strings(32,240).put("description","Exact workspace-relative UTF-8 inputs. Ancestor AGENTS.md files are included automatically. No directories, symlinks or dependency caches."))
                .put("write_paths",strings(8,240).put("description","coder only: explicit file ownership, disjoint between siblings. Existing targets are also snapshotted. No deletion or AGENTS.md edits."))
                .put("allow_write",JSONObject().put("type","boolean").put("description","Default false; coder must explicitly enable proposal writes.")))
        val p=JSONObject().put("request_key",text(64,"Stable idempotency key for this parent task origin; reuse only with identical inputs.").put("pattern","^[a-zA-Z0-9_-]+$"))
            .put("reason",text(40).put("enum",JSONArray(listOf("independent_investigation","independent_review","disjoint_coding"))))
            .put("tasks",JSONObject().put("type","array").put("minItems",1).put("maxItems",3).put("items",task))
            .put("parallel",JSONObject().put("type","boolean").put("description","Default true; false runs the same isolated assignments serially for comparison."))
            .put("group_id",text(32).put("pattern","^[a-f0-9]{32}$")).put("task_id",text(40).put("pattern","^[a-zA-Z0-9_-]+$"))
            .put("view",text(20).put("enum",JSONArray(listOf("result","evidence","artifact"))).put("description","read defaults to result; artifact needs task_id/path, evidence needs task_id. Text pages are at most 8000 characters."))
            .put("path",text(240)).put("offset",JSONObject().put("type","integer").put("minimum",0).put("maximum",2000000))
            .put("expected_proposal",text(64,"Exact proposal digest returned for a ready coding child; verify artifacts before merging.").put("pattern","^[a-f0-9]{64}$"))
        return ActionSchema.build(p,fields,::required)
    }
    fun canonical(value: Any?): String = when(value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix="{",postfix="}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix="[",postfix="]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    fun validate(args: JSONObject) {
        ToolInput.check(args,schema())
        if(args.optString("action")=="read") {
            val view=args.optString("view","result")
            require(view=="result" || args.has("task_id")) { "Evidence/artifact reads require task_id." }
            require(view!="artifact" || args.has("path")) { "Artifact reads require path." }
        }
    }
    fun specification()=JSONObject().put("type","function").put("function",JSONObject().put("name","agent_delegate")
        .put("description","Delegate selected independent local investigation, review or disjoint code proposals to 1–3 bounded children using the parent model and shared allowance. run waits for structured results with a 120-second group deadline; at most 2 children run app-wide, 4 inference rounds and 16 tool calls per child, sharing 8 delegation attempts and 48000 token credits per parent turn. Only declared UTF-8 file snapshots and explicit parent evidence are passed, never full chat/persona/accounts. Children cannot use shell, phone, web, MCP, memory or delegation. Coding writes stay private until merge checks source/rule versions and current requirements through normal file tools. read inspects scoped receipts, evidence or artifacts; list recovers IDs; discard retires drafts. Repeated request_key never replays work. Interrupted merges require actual-state inspection, not retry. Parent must verify merged artifacts and deliver the answer.")
        .put("parameters",schema()))
    fun finish()=TaskState.spec("delegate_finish","Return a bounded sourced assessment, not a claim that the parent goal is complete. Completed results require actual successful child tool-call IDs or p:-prefixed supplied parent evidence. Coding results are proposals; no build was run. Report uncertainty and failures explicitly.",JSONObject()
        .put("outcome",text(20).put("enum",JSONArray(listOf("completed","partial","blocked")))).put("summary",text(1200))
        .put("evidence",strings(8,200)).put("findings",strings(6,500)).put("unresolved",strings(6,300)),listOf("outcome","summary","evidence","unresolved"))
    const val guidance="Use agent_delegate selectively for independent source investigation, review or disjoint file proposals, only when parallel value exceeds coordination and extra model cost. Prefer direct tools for a small edit, one lookup, dependent steps or phone/execution work. Supply exact files, separate ownership, goal and constraints; user requirements are carried separately and oversized context is refused. Children share request/token credits and cannot expand capabilities or spawn descendants. Child conclusions and code proposals are not parent verification. Inspect results/artifacts, merge only an expected proposal with unchanged inputs/rules/current requirements, then run relevant checks in the parent. Never mark the user goal complete solely because delegation returned. Use serial mode for controlled comparisons; it is not automatically cheaper or more correct."
}
