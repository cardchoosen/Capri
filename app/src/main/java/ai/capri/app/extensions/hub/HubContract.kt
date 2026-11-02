package ai.capri.app.extensions.hub

import ai.capri.app.agent.TaskState
import org.json.JSONArray
import org.json.JSONObject

internal object HubContract {
    const val guidance="Only prepare a public contribution when the user explicitly requests public sharing or a PR. Creating, installing or updating a Skill alone does not request publication; do not proactively prepare previews. Startup and read-only status checks never open approval. Public Skill contribution requires native user confirmation of a frozen file preview. Preparing or discussing a Skill is not publication approval. Never use shell, Git, HTTP or MCP tools to bypass this confirmation. Public catalog download/update does not execute resources and must preserve personal edits."
    fun specification():JSONObject=TaskState.spec("skill_hub","Inspect the official public Skill catalog and optional user contributions. prepare is only for explicit user requests to contribute publicly; it freezes a validated workspace Skill and asks the user through the native UI before any upload. Only the user's native confirmation can publish; no model approval or submit action exists. list/read inspect scoped receipts and actual PR URLs. A prepared or submitted contribution is not merged, and public distribution does not back up private Skills.",JSONObject()
        .put("action",JSONObject().put("type","string").put("enum",JSONArray(listOf("status","prepare","list","read"))))
        .put("path",JSONObject().put("type","string").put("minLength",1).put("maxLength",512).put("description","prepare: current-chat workspace directory containing SKILL.md and up to 50 text files, total 1 MB."))
        .put("id",JSONObject().put("type","string").put("minLength",1).put("maxLength",64).put("description","prepare: portable lowercase Skill ID, matching its name."))
        .put("key",JSONObject().put("type","string").put("minLength",64).put("maxLength",64).put("description","read: exact prepared contribution key.")),listOf("action"))
    fun validate(args:JSONObject) {
        ai.capri.app.agent.ToolInput.check(args,specification().getJSONObject("function").getJSONObject("parameters"))
        val allowed=when(args.getString("action")){"prepare"->setOf("action","path","id");"read"->setOf("action","key");else->setOf("action")}
        require(args.keys().asSequence().toSet()==allowed) { "Use exactly the required fields for this public Skill action." }
    }
}
