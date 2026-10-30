package ai.capri.app.program

import ai.capri.app.agent.TaskState
import org.json.JSONArray
import org.json.JSONObject

internal object ProgramContract {
    val reads = setOf("read_file", "list_files", "search_files", "project", "web_search", "web_read")
    val writes = setOf("write_file", "edit_file", "apply_patch")
    fun validate(args: JSONObject): JSONObject {
        val action=args.optString("action")
        val fields=when(action) {
            "execute" -> setOf("action","code","timeout_ms","max_calls","max_output_chars")
            "batch" -> setOf("action","calls","timeout_ms","max_calls","max_output_chars")
            "read" -> setOf("action","run_id","offset")
            "status","reset" -> setOf("action")
            else -> error("Unknown program action.")
        }
        require(args.keys().asSequence().all { it in fields }) { "Unknown program argument." }
        if(action=="execute") require(args.opt("code") is String && args.getString("code").length in 1..64000 && '\u0000' !in args.getString("code")) { "code must contain 1..64000 characters without NUL." }
        if(action in setOf("execute","batch")) {
            for((key,range) in mapOf("timeout_ms" to 100..60000,"max_calls" to 1..32,"max_output_chars" to 1024..16000)) if(args.has(key))
                require(args.opt(key) is Int && args.getInt(key) in range) { "Invalid program budget: $key." }
            if(action=="batch") calls(args.getJSONArray("calls"),args.optInt("max_calls",16),true)
        }
        if(action=="read") {
            require(args.opt("run_id") is String && args.getString("run_id").matches(Regex("[0-9a-f-]{36}"))) { "Invalid program run id." }
            if(args.has("offset")) require(args.opt("offset") is Int && args.getInt("offset") >= 0) { "offset must be a nonnegative integer." }
        }
        return JSONObject(args.toString())
    }
    fun calls(array: JSONArray, remaining: Int, readOnly: Boolean): List<JSONObject> {
        require(array.length() in 1..minOf(32,remaining)) { "Program tool-call budget exceeded." }
        return (0 until array.length()).map { i ->
            val item=array.getJSONObject(i)
            require(item.keys().asSequence().toSet()==setOf("name","arguments") && item.opt("name") is String && item.opt("arguments") is JSONObject) { "Each RPC requires only name and object arguments." }
            require(item.getString("name") in reads + if(readOnly) emptySet() else writes) { "RPC target is not allowed; batches support read-only targets. Use normal tools for control, authorization, services and processes." }
            require(item.getJSONObject("arguments").toString().length<=128000) { "RPC arguments exceed 128000 characters." }
            JSONObject(item.toString())
        }
    }
    fun specification(): JSONObject {
        val spec = TaskState.spec("program_run", "Run bounded Python orchestration in a revision-scoped persistent chat kernel, or batch independent read-only tools. execute exposes capri.call(name, arguments), capri.batch([{name,arguments}]) and capri.emit(value). RPC supports workspace read/list/search/project/write/edit/patch and web search/read; batches allow reads only. Use known exact contracts; RPC uses normal validation, permissions and scope. Emit selected summaries; intermediate RPC results stay in execution and durable run receipts. Any failed RPC makes the run partial even if caught. Variables/imports persist until reset, idle expiry (60s), environment/revision change, error, cancellation or restart; never assume persistence without checking status. Python has the same OS authority as python_run, not a security sandbox. Capture subprocess output explicitly; raw stdin/file-descriptor protocol writes and background threads/processes are unsupported. Use terminal for long work. Default 10s wall time including queue/start/RPC, 16 calls, 8000 output characters; maximum 60s/32 calls/16000 output, 384 MiB address space and two kernels per app. read pages a run receipt; unknown intents must be verified, never replayed. Returned invocation/exit status is not proof of goal completion.",JSONObject()
        .put("action",JSONObject().put("type","string").put("enum",JSONArray(listOf("execute","batch","status","reset","read"))))
        .put("code",JSONObject().put("type","string").put("minLength",1).put("maxLength",64000))
        .put("calls",JSONObject().put("type","array").put("minItems",1).put("maxItems",32).put("items",JSONObject().put("type","object").put("additionalProperties",false)
            .put("properties",JSONObject().put("name",JSONObject().put("type","string")).put("arguments",JSONObject().put("type","object"))).put("required",JSONArray(listOf("name","arguments")))))
        .put("timeout_ms",JSONObject().put("type","integer").put("minimum",100).put("maximum",60000))
        .put("max_calls",JSONObject().put("type","integer").put("minimum",1).put("maximum",32))
        .put("max_output_chars",JSONObject().put("type","integer").put("minimum",1024).put("maximum",16000))
        .put("run_id",JSONObject().put("type","string"))
        .put("offset",JSONObject().put("type","integer").put("minimum",0)),listOf("action"))
        val function=spec.getJSONObject("function")
        val properties=function.getJSONObject("parameters").getJSONObject("properties")
        properties.getJSONObject("calls").getJSONObject("items").getJSONObject("properties").getJSONObject("name").put("enum",JSONArray(reads.toList()))
        val fields=mapOf("execute" to setOf("code","timeout_ms","max_calls","max_output_chars"),
            "batch" to setOf("calls","timeout_ms","max_calls","max_output_chars"), "read" to setOf("run_id","offset"), "status" to emptySet(),"reset" to emptySet())
        function.put("parameters",ai.capri.app.ActionSchema.build(properties,fields,{ action -> when(action){"execute"->listOf("code");"batch"->listOf("calls");"read"->listOf("run_id");else->emptyList()} }))
        return spec
    }
}
