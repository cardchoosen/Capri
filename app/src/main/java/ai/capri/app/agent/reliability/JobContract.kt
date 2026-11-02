package ai.capri.app.agent.reliability

import ai.capri.app.agent.TaskState
import org.json.JSONArray
import org.json.JSONObject

internal object JobContract {
    val guidance="Use agent_jobs to save a durable goal, inspect task events, or schedule isolated read-only workspace analysis. Set allow_background only when the user authorized unattended model requests. Saved goals and queued runs are not completed work. General goals retain a checkpoint reference and require foreground continuation; unattended analysis cannot use shell, phone, browser, accounts, memory or recursive delegation. Times are Unix milliseconds; Android delivery is inexact. No automatic provider/account fallback. Review interrupted runs and unknown effects before explicit retry."
    fun specification():JSONObject {
        fun text(max:Int)=JSONObject().put("type","string").put("minLength",1).put("maxLength",max)
        return TaskState.spec("agent_jobs","Manage durable goals and bounded analysis jobs in this history scope. create is idempotent by request_key. list/read expose ownership, states and retained results. run explicitly starts a fresh analysis occurrence, or returns a general goal for foreground continuation. pause/cancel prevent future dispatch; in-flight requests are cancelled at safe boundaries. resume rearms paused jobs. complete settles a general goal only with positive recorded work call IDs and summary; it never starts tools. events pages metadata with a gap flag; lineage identifies checkpoints, compaction and history edits.",JSONObject()
            .put("action",JSONObject().put("type","string").put("enum",JSONArray(listOf("create","list","read","run","pause","resume","cancel","complete","events"))))
            .put("request_key",text(80)).put("id",text(80)).put("goal",text(2000))
            .put("mode",JSONObject().put("type","string").put("enum",JSONArray(listOf("checkpoint","analysis"))))
            .put("trigger",JSONObject().put("type","string").put("enum",JSONArray(listOf("manual","once","interval","event"))))
            .put("allow_background",JSONObject().put("type","boolean").put("description","True only for explicitly authorized unattended read-only model analysis; defaults to false."))
            .put("files",JSONObject().put("type","array").put("maxItems",16).put("items",text(240)).put("description","Exact UTF-8 workspace-relative input files, not directories. No hidden/private dependency paths."))
            .put("due_at",JSONObject().put("type","integer").put("minimum",0))
            .put("interval_ms",JSONObject().put("type","integer").put("minimum",900000).put("maximum",2592000000L))
            .put("event_kind",JSONObject().put("type","string").put("enum",JSONArray(AgentJobs.eventKinds.toList())))
            .put("source_id",text(100).put("description","Optional exact process/diagnostic/origin ID; otherwise matches the next supported event in this scope."))
            .put("summary",text(2000)).put("evidence",JSONObject().put("type","array").put("minItems",1).put("maxItems",16).put("items",text(200)))
            .put("after",JSONObject().put("type","integer").put("minimum",0)),listOf("action"))
    }
    fun validate(args:JSONObject) {
        ai.capri.app.agent.ToolInput.check(args,specification().getJSONObject("function").getJSONObject("parameters"))
        val action=args.getString("action")
        val allowed=when(action) { "create"->setOf("action","request_key","goal","mode","trigger","allow_background","files","due_at","interval_ms","event_kind","source_id");"complete"->setOf("action","id","summary","evidence");"list"->setOf("action");"events"->setOf("action","after");else->setOf("action","id") }
        require(args.keys().asSequence().all { it in allowed }) { "Unexpected arguments for this job action." }
        for(field in if(action=="create")listOf("request_key","goal","mode","trigger") else if(action=="complete")listOf("id","summary","evidence") else if(action in setOf("list","events"))emptyList() else listOf("id"))require(args.has(field)) { "Missing job argument: $field." }
    }
}
