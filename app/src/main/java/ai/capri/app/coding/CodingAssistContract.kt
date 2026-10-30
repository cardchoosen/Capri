package ai.capri.app.coding

import ai.capri.app.ActionSchema
import ai.capri.app.agent.TaskState
import org.json.JSONArray
import org.json.JSONObject

internal object CodingAssistContract {
    fun specification():JSONObject {
        val properties=JSONObject().put("path",JSONObject().put("type","string").put("minLength",1).put("maxLength",1024)).put("baseline_snapshot_id",JSONObject().put("type","string").put("pattern","^[0-9a-f-]{36}$")).put("python_lsp",JSONObject().put("type","boolean"))
            .put("job_id",JSONObject().put("type","string").put("pattern","^[0-9a-f-]{36}$"))
            .put("wait_ms",JSONObject().put("type","integer").put("minimum",0).put("maximum",5000))
        val fields=mapOf("check" to setOf("path","baseline_snapshot_id"),"configure" to setOf("python_lsp"),"snapshots" to emptySet(),"result" to setOf("job_id","wait_ms"))
        val spec=TaskState.spec("coding_diagnostics","Check selected JSON/XML immediately or queue a selected current Python file with check (optional matching baseline_snapshot_id), configure optional Python pylsp analysis for this chat revision, inspect retained edit snapshots, or read a versioned diagnostic job. Writes return immediate bounded JSON/XML findings and queue Python analysis without waiting. New/unchanged/resolved findings are distinguished; missing baseline, unavailable server, stale source/environment or interrupted jobs never mean clean. result wait_ms optionally waits up to 5000 ms; avoid zero-wait polling. configure installs nothing: explicitly prepare python-lsp-server[pyflakes] through environment when authorized. Analysis never runs project code, commands or build hooks. Findings are not compilation/test proof. Oldest committed snapshots may be evicted at 128 records/64 MiB per scope; pending records are retained.",properties,emptyList())
        spec.getJSONObject("function").put("parameters",ActionSchema.build(properties,fields,{when(it){"check"->listOf("path");"configure"->listOf("python_lsp");"result"->listOf("job_id");else->emptyList()}}));return spec
    }
    fun validate(args:JSONObject){
        ai.capri.app.agent.ToolInput.check(args,specification().getJSONObject("function").getJSONObject("parameters"))
        require(args.optString("action") in setOf("check","configure","snapshots","result")){"Unknown coding diagnostics action."}
        if(args.has("job_id"))require(runCatching{java.util.UUID.fromString(args.getString("job_id")).toString()==args.getString("job_id")}.getOrDefault(false)){"Invalid diagnostic job id."}
        if(args.has("wait_ms"))require(args.opt("wait_ms") is Int && args.getInt("wait_ms") in 0..5000){"wait_ms must be an integer in 0..5000."}
    }
}
