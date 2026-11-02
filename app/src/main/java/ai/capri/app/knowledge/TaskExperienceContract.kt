package ai.capri.app.knowledge

import ai.capri.app.ActionSchema
import org.json.JSONArray
import org.json.JSONObject

internal object TaskExperienceContract {
    private val fields=mapOf("list" to setOf("offset"),"read" to setOf("experience_id"),"draft" to setOf("experience_id","skill_id","description","triggers","steps","validation","failures"),
        "feedback" to setOf("skill_id","kind","text","source_call","source_entry","excerpt"),"revisions" to setOf("skill_id"),"feedback_history" to setOf("skill_id","scope","offset"),"compare" to setOf("skill_id","before","after"),"restore" to setOf("skill_id","revision","expected_revision"))
    private fun required(action: String)=when(action) { "list" -> emptyList(); "feedback_history" -> listOf("skill_id"); "feedback" -> listOf("skill_id","kind","text");else -> fields.getValue(action).toList() }
    private fun schema(): JSONObject {
        fun text(max: Int,description: String="")=JSONObject().put("type","string").put("minLength",1).put("maxLength",max).put("description",description)
        val p=JSONObject().put("experience_id",text(32,"Current-chat task revision candidate ID.").put("pattern","^[a-f0-9]{32}$"))
            .put("skill_id",text(64,"Existing Skill ID for feedback/revisions/restore, portable new ID for draft.").put("pattern","^[a-z0-9]+(-[a-z0-9]+)*$"))
            .put("description",text(1024,"English capability and trigger summary."))
        for(field in listOf("triggers","steps","validation","failures"))p.put(field,text(1600,"Concise English $field, grounded in evidence. Include checks and real failure branches, not a transcript."))
        for(field in listOf("revision","expected_revision","before","after"))p.put(field,text(64,"Exact retained content hash; expected_revision must match the installed Skill before restore.").put("pattern","^[a-f0-9]{64}$"))
        p.put("offset",JSONObject().put("type","integer").put("minimum",0).put("maximum",64).put("description","Result offset for list/feedback_history, default 0."))
            .put("scope",JSONObject().put("type","string").put("enum",JSONArray(listOf("skill_metadata","current_task"))).put("description","feedback_history: default skill_metadata returns cross-chat metadata; current_task returns only this task revision feedback text."))
        p.put("kind",text(32).put("enum",JSONArray(listOf("failure","expired_dependency","user_correction"))))
            .put("text",text(800,"Observed issue; do not infer the Skill caused it."))
            .put("source_call",text(200,"Actual unsuccessful tool call ID in this task."))
            .put("source_entry",JSONObject().put("type","integer").put("minimum",1))
            .put("excerpt",text(800,"Exact excerpt from the referenced user correction."))
        val schema=ActionSchema.build(p,fields,::required)
        val branches=schema.getJSONArray("oneOf")
        for(i in 0 until branches.length()) {
            val branch=branches.getJSONObject(i)
            if(branch.getJSONObject("properties").getJSONObject("action").getJSONArray("enum").getString(0)=="feedback") {
                branch.put("anyOf",JSONArray().put(JSONObject().put("properties",JSONObject().put("kind",JSONObject().put("enum",JSONArray(listOf("user_correction"))))).put("required",JSONArray(listOf("source_entry","excerpt"))))
                    .put(JSONObject().put("properties",JSONObject().put("kind",JSONObject().put("enum",JSONArray(listOf("failure","expired_dependency"))))).put("required",JSONArray(listOf("source_call")))))
            }
        }
        return schema
    }
    fun specification()=JSONObject().put("type","function").put("function",JSONObject().put("name","task_experience")
        .put("description","Review chat/revision-scoped completed-task candidates and observed Skill feedback; feedback_history returns bounded cross-chat metadata for one Skill revision lineage without copying source text. list/read retrieve evidence; draft requires a captured positive conclusion and loaded skill-creator, writes an English workspace proposal through file rules, never installs it. feedback requires a real failed call or exact user correction. revisions/compare inspect managed installed Skill history; restore changes a user Skill only when expected_revision matches and preserves permissions/enablement. Built-ins cannot be restored. Existing Skill discovery, loading and import interfaces remain authoritative.")
        .put("parameters",schema()))
    fun validate(args: JSONObject) { ai.capri.app.agent.ToolInput.check(args,schema()) }
    const val guidance="After verified reusable work, task_state.finish captures a bounded task_experience candidate with actual call references. Read it and load skill-creator before proposing an English Skill draft; validate and import through the existing Skill workflow. Record failures, stale dependencies and user corrections against actual Skill revisions; feedback is an observation, not proven causality or permission to rewrite instructions. Compare revisions and use guarded restore when requested. Never install a candidate automatically or turn a one-off workaround into a universal rule."
}
