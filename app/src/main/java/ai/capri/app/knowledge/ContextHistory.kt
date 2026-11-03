package ai.capri.app.knowledge

import ai.capri.app.Entry
import ai.capri.app.RecoveryMessages
import ai.capri.app.model.ContextBudget
import ai.capri.app.extensions.SkillContext
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Android storage, Skill and event adapters; all compaction policy lives in agent-core. */
internal class ContextHistory(
    root: File,
    readText: (File) -> String = { AtomicFile(it).openRead().bufferedReader().use { reader -> reader.readText() } },
    writeText: (File, String) -> Unit = { file, text ->
        val target = AtomicFile(file); val stream = target.startWrite()
        try { stream.write(text.toByteArray()); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    },
    strategy: ContextStrategy = PressureContextStrategy()
) {
    private val archive = ContextArchive(root,readText,writeText,strategy,RecoveryMessages::sanitized) { chat,messages,id ->
        ai.capri.app.agent.reliability.AgentEvents(File(root.parentFile,"agent-events")).transition(chat,messages,"compaction",id)
    }
    private fun entry(e: Entry, manifest:Boolean=false) = ContextEntry(e.role,e.text,e.id,e.inputs,
        if(manifest) ai.capri.app.InputAttachments.manifest(e.inputs?.let { raw -> JSONArray(raw).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } } ?: emptyList()) else "")
    fun summary(chat:String)=archive.summary(chat)
    fun finish(chat:String,messages:JSONArray)=archive.finish(chat,messages)
    fun fresh(chat:String,entries:List<Entry>,rules:String)=archive.fresh(chat,SkillContext.freshEntries(entries).map { entry(it,true) },rules,entries.filter { it.role=="user" }.map { it.id }.toSet())
    fun fit(chat:String,messages:JSONArray,tools:JSONArray,budget:ContextBudget)=archive.fit(chat,messages,tools,budget)
    fun boundResult(chat:String,name:String,text:String,limit:Int=16000)=archive.boundResult(chat,name,text,limit)
    suspend fun prepare(chat:String,messages:JSONArray,tools:JSONArray,budget:ContextBudget,summarize:suspend (JSONArray,Int)->String)=archive.prepare(chat,messages,tools,budget,summarize)
    fun budget(chat:String)=archive.budget(chat)
    fun query(chat:String,entries:List<Entry>,args:JSONObject)=archive.query(chat,entries.map { entry(it) },args)
}
