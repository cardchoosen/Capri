package ai.capri.app.agent.rules

import ai.capri.app.agent.reliability.DurableJson
import ai.capri.app.knowledge.MemoryPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** User-authored configuration, separate from persona, memory, Skills and untrusted project files. */
internal class RulesStore(private val root:File) {
    private fun document(scope:String,chat:String):DurableJson {
        require(scope in setOf("global","chat")) { "Rule scope must be global or chat." }
        require(chat.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid rule owner." }
        return DurableJson(File(root,if(scope=="global")"global.json" else "chats/$chat/rules.json"))
    }
    fun read(scope:String,chat:String):JSONObject=document(scope,chat).access { data -> JSONObject().put("revision",data.optLong("revision")).put("rules",data.optJSONArray("rules")?:JSONArray()).put("scope",scope) }
    fun save(scope:String,chat:String,id:String,title:String,content:String,enabled:Boolean,expected:Long):JSONObject=document(scope,chat).access { data ->
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && title.isNotBlank() && title.length<=80 && content.isNotBlank() && content.length<=1000) { "Rules require a stable ID, a 1..80 character title and 1..1000 character content." }
        require(data.optLong("revision")==expected) { "Rules changed; reload before saving to avoid overwriting another edit." }
        val old=data.optJSONArray("rules")?:JSONArray();val values=(0 until old.length()).map { old.getJSONObject(it) }.filter { it.getString("id")!=id }.toMutableList()
        values+=JSONObject().put("id",id).put("title",title).put("content",content).put("enabled",enabled)
        require(values.size<=20 && values.sumOf{it.getString("content").length+it.getString("title").length}<=3000) { "Each scope allows 20 rules and 3000 characters including titles; shorten or remove rules instead of truncating instructions." }
        data.put("rules",JSONArray(values)).put("revision",expected+1)
        JSONObject().put("status","ok").put("revision",expected+1).put("scope",scope).put("effective","next_turn")
    }
    fun delete(scope:String,chat:String,id:String,expected:Long):JSONObject=document(scope,chat).access { data ->
        require(data.optLong("revision")==expected) { "Rules changed; reload before deleting." }
        val old=data.optJSONArray("rules")?:JSONArray();val values=(0 until old.length()).map{old.getJSONObject(it)}.filter{it.optString("id")!=id}
        require(values.size<old.length()) { "Rule not found in this scope." }
        data.put("rules",JSONArray(values)).put("revision",expected+1)
        JSONObject().put("status","ok").put("revision",expected+1).put("effective","next_turn")
    }
    fun snapshot(chat:String):JSONObject {
        val rules=JSONArray()
        for(scope in listOf("global","chat")) { val items=read(scope,chat).getJSONArray("rules");for(i in 0 until items.length())items.getJSONObject(i).takeIf{it.optBoolean("enabled",true)}?.let { rules.put(JSONObject(it.toString()).put("scope",scope)) } }
        return JSONObject().put("rules",rules).put("hash",MemoryPolicy.hash(rules.toString())).put("policy","current_user_request_over_chat_over_global; project_rules_are_scoped_guidance; core_permissions_unchanged")
    }
}
