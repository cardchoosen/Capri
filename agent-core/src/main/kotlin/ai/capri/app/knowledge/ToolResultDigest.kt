package ai.capri.app.knowledge

import org.json.JSONArray
import org.json.JSONObject

/** A preview is never the complete result or proof of task success. Original bytes remain archived. */
object ToolResultDigest {
    private val metadata = setOf("id","kind","published_at","provider","status","state","action","exit_code","success","applied","path","name","sha256","mime","size","count","total","total_chars","next_offset","offset","has_more","cursor","next","command_id","terminal_id","environment_receipt","url","title","reference","citation","source","source_id","line","line_number","start_line","end_line","reason","truncated","cached","next_line","next_column","column","scan_truncated","fetched_at","read_at","bytes","partial")
    private val textFields = setOf("output","stdout","stderr","text","content","snippet","preview")
    private fun clip(text:String, limit:Int):String {
        if(text.length<=limit)return text
        return text.take(limit/2).dropLastWhile{it.isHighSurrogate()}+"\n[excerpt omitted]\n"+text.takeLast(limit/2).dropWhile{it.isLowSurrogate()}
    }
    fun kind(name:String)=when(name){"terminal","python_run"->"execution_log";"web_search","web_read"->"web_sources";"read_file","list_files","search_files","project"->"file_evidence";else->"generic"}
    fun preview(name:String,text:String,reference:String,limit:Int):JSONObject {
        require(limit >= 1024) { "Tool preview limit must be at least 1024 characters." }
        val original=runCatching{JSONObject(text)}.getOrNull()
        val base=JSONObject().put("status",original?.optString("status")?.takeIf{it.isNotBlank()}?:"result_offloaded")
            .put("result_incomplete",ai.capri.app.agent.ResultSignals.unsuccessful(text)).put("truncated",true).put("reference",reference).put("total_chars",text.length).put("preview_kind",kind(name))
            .put("notice","Partial preview only. Read the archived result with context_history reference/offset; do not repeat operations to recover output. A returned invocation is not proof of success.")
        var textLimit=(limit/6).coerceAtLeast(64);var items=8
        fun digest(value:JSONObject,depth:Int):JSONObject=JSONObject().apply {
            value.keys().asSequence().toList().forEach { key ->
                val v=value.opt(key)
                when {
                    key in metadata && (v is Number || v is Boolean || v==JSONObject.NULL)->put(key,v)
                    key in metadata && v is String ->put(key,clip(v, minOf(textLimit,800)))
                    key in textFields && v is String ->put(key,clip(v,textLimit))
                    key=="error" && v!=null && v!=JSONObject.NULL ->put(key,if(v is JSONObject) clip(v.toString(),textLimit) else clip(v.toString(),textLimit))
                    depth<3 && key in setOf("data","command","result") && v is JSONObject ->put(key,digest(v,depth+1))
                    depth<3 && key in setOf("results","matches","items","files","entries","sources") && v is JSONArray ->{
                        put(key,JSONArray((0 until minOf(items,v.length())).map { i -> val item=v.opt(i);if(item is JSONObject)digest(item,depth+1)else clip(item.toString(),textLimit/2) }))
                        put(key+"_omitted",(v.length()-items).coerceAtLeast(0))
                    }
                }
            }
        }
        while(true){
            val result=JSONObject(base.toString()).put("preview_head",text.take(minOf(text.length,textLimit)).dropLastWhile{it.isHighSurrogate()}).put("preview_tail",text.takeLast(textLimit).dropWhile{it.isLowSurrogate()})
            if(original!=null){val d=digest(original,0);d.keys().asSequence().toList().forEach { key -> if(key == "reference") result.put("source_reference",d.get(key)) else if(key !in setOf("status","truncated","total_chars","notice"))result.put(key,d.get(key)) }}
            if(original==null && text.startsWith("exit="))text.lineSequence().first().substringAfter("exit=").toIntOrNull()?.let{result.put("exit_code",it)}
            if(result.toString().length<=limit)return result
            if(textLimit<=64){
                var amount=32
                while(amount>=1){val minimal=JSONObject(base.toString()).put("preview_head",text.take(amount).dropLastWhile{it.isHighSurrogate()}).put("preview_tail",text.takeLast(amount).dropWhile{it.isLowSurrogate()});if(minimal.toString().length<=limit)return minimal;amount/=2}
                return base
            }
            textLimit=(textLimit/2).coerceAtLeast(64);items=(items/2).coerceAtLeast(1)
        }
    }
}
