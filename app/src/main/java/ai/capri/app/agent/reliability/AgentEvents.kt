package ai.capri.app.agent.reliability

import ai.capri.app.HistoryEditing
import ai.capri.app.knowledge.MemoryPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Canonical metadata stream. Payloads, credentials, prompts and command output are excluded. */
internal class AgentEvents(private val root: File,private val clock: ()->Long=System::currentTimeMillis) {
    companion object {
        val kinds=setOf("turn_started","phase_changed","tool_intent","tool_result","turn_finished","checkpoint","resume","compaction","history_edit","process_completed","coding_ready","job_changed","job_finished")
        private val fields=setOf("state","origin_id","call_id","tool","checkpoint_hash","archive_id","source_id","job_id","run_id","purpose","attempts","tokens","duration_ms","parent_node","source_hash","context_id")
    }
    private fun store(scope:String)=DurableJson(File(root,"${HistoryEditing.scope(scope).first}/journal.json"))
    fun emit(scope:String,kind:String,values:JSONObject=JSONObject()):JSONObject {
        require(kind in kinds) { "Unknown task event kind." }
        require(values.keys().asSequence().all { it in fields }) { "Task events accept metadata only." }
        require(values.toString().length<=2000 && values.keys().asSequence().all { values.opt(it) is String || values.opt(it) is Number || values.isNull(it) }) { "Invalid task event metadata." }
        return store(scope).access { data ->
            val sequence=data.optLong("sequence")+1;val events=data.optJSONArray("events")?:JSONArray()
            val event=JSONObject(values.toString()).put("schema",1).put("sequence",sequence).put("event_id",UUID.randomUUID().toString()).put("scope",scope).put("kind",kind).put("at",clock())
            events.put(event);data.put("sequence",sequence).put("events",JSONArray((maxOf(0,events.length()-512) until events.length()).map { events.getJSONObject(it) }))
            JSONObject(event.toString())
        }
    }
    fun read(scope:String,after:Long=0):JSONObject {
        require(after>=0) { "Event cursor must be nonnegative." }
        return store(scope).access { data ->
            val all=data.optJSONArray("events")?:JSONArray();val first=all.optJSONObject(0)?.optLong("sequence")?:1L
            val page=(0 until all.length()).map { all.getJSONObject(it) }.filter { it.getLong("sequence")>after && it.getString("scope")==scope }.take(64)
            JSONObject().put("schema",1).put("status","ok").put("events",JSONArray(page)).put("cursor",page.lastOrNull()?.getLong("sequence")?:maxOf(after,data.optLong("sequence")))
                .put("gap",after<first-1).put("latest",data.optLong("sequence"))
        }
    }
    /** The lineage pointer is embedded in the actual checkpoint, linking transitions rather than timestamps. */
    fun transition(scope:String,messages:JSONArray,kind:String,source:String=""):JSONObject {
        require(kind in setOf("checkpoint","resume","compaction"))
        val system=messages.getJSONObject(0);val previous=system.optJSONObject("_capri_lineage")
        val normalized=ai.capri.app.RecoveryMessages.sanitized(messages)
        val safe=JSONObject(normalized.getJSONObject(0).toString()).apply { remove("content");remove("_capri_lineage") }
        val hash=MemoryPolicy.hash(safe.toString()+JSONArray((1 until messages.length()).map { normalized.getJSONObject(it) }).toString())
        if(kind=="checkpoint" && previous?.optString("checkpoint_hash")==hash)return previous
        val metadata=JSONObject().put("checkpoint_hash",hash).put("parent_node",previous?.optString("event_id")?:"").put("source_id",source.take(100))
        val event=emit(scope,kind,metadata)
        system.put("_capri_lineage",JSONObject().put("event_id",event.getString("event_id")).put("checkpoint_hash",hash).put("scope",scope).put("kind",kind))
        return event
    }
}
