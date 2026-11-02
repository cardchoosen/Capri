package ai.capri.app.knowledge

import ai.capri.app.*
import ai.capri.app.agent.CompletionEvidence
import ai.capri.app.agent.TaskState
import ai.capri.app.agent.ToolRegistry
import ai.capri.app.extensions.SkillHistory
import ai.capri.app.extensions.SkillVersions
import ai.capri.app.files.WorkspaceFileTools
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Chat/revision-local evidence and proposals. No automatic memory promotion, Skill install or execution. */
internal class TaskExperience(private val root: File,private val workspace: Workspace) {
    companion object {
        private val sharedLock=Any()
        private fun write(file: File,value: JSONObject) { file.parentFile!!.mkdirs();val tmp=File(file.parentFile,file.name+".tmp");java.io.FileOutputStream(tmp).use { it.write(value.toString().toByteArray());it.fd.sync() };Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        fun deleteSharedFeedback(filesDir: File,chat: String) = synchronized(sharedLock) {
            File(filesDir,"skill-feedback").listFiles().orEmpty().filter { it.extension=="json" }.forEach { file ->
                val value=JSONObject(file.readText());val all=value.getJSONArray("events")
                val kept=(0 until all.length()).map { all.getJSONObject(it) }.filterNot { HistoryEditing.scope(it.getString("scope")).first==chat }
                if(kept.isEmpty())check(file.delete()) { "Unable to remove Skill feedback metadata." } else write(file,value.put("events",JSONArray(kept)))
            }
        }
    }
    private fun share(scope: String,event: JSONObject,skills: JSONArray) = synchronized(sharedLock) {
        val folder=File(root.parentFile,"skill-feedback").apply { mkdirs() }
        for(i in 0 until skills.length()) {
            val skill=skills.getJSONObject(i);val revision=skill.optString("revision")
            if(!revision.matches(Regex("[a-f0-9]{64}")))continue
            val id=ai.capri.app.extensions.ExtensionPolicy.id(skill.getString("id"));val file=File(folder,"$id.json");val state=read(file)
            val all=state.optJSONArray("events")?:JSONArray();val eventId=MemoryPolicy.hash(event.getString("event_id")+id+revision+skill.optString("source"))
            if((0 until all.length()).any { all.getJSONObject(it).getString("event_id")==eventId })continue
            val metadata=JSONObject().put("event_id",eventId).put("scope",scope).put("skill_id",id).put("revision",revision).put("source",skill.optString("source"))
                .put("kind",event.getString("kind")).put("at",event.getLong("at")).put("causality_verified",false)
            for(key in listOf("source_call","entry_id","source_hash","result_hash","tool"))if(event.has(key))metadata.put(key,event.get(key))
            write(file,JSONObject().put("skill_id",id).put("events",JSONArray(((0 until all.length()).map { all.getJSONObject(it) }+metadata).takeLast(32))))
        }
        folder.listFiles().orEmpty().filter { it.extension=="json" }.sortedByDescending { it.lastModified() }.drop(64).forEach { check(it.delete()) { "Unable to retire old Skill feedback metadata." } }
    }
    private fun folder(scope: String): File { val (chat,revision,legacy)=HistoryEditing.scope(scope);return File(root,"$chat/${if(legacy)"branches" else "revisions"}/${revision ?: "main"}").apply { mkdirs() } }
    private fun atomic(file: File,value: JSONObject) = write(file,value)
    private fun read(file: File)=if(file.isFile)JSONObject(file.readText()) else JSONObject()
    private fun id(value: String)=value.also { require(it.matches(Regex("[a-f0-9]{32}"))) { "Invalid experience ID." } }
    @Synchronized fun capture(scope: String,messages: JSONArray): JSONObject {
        val state=TaskState.snapshot(messages);val conclusion=state.optJSONObject("conclusion")?:return JSONObject().put("status","not_captured")
        if(conclusion.optString("outcome")!="completed")return JSONObject().put("status","not_captured")
        val refs=conclusion.optJSONArray("evidence")?:return JSONObject().put("status","not_captured")
        if(refs.length()==0 || MemoryPolicy.credentialValue(state.optString("request")+conclusion.optString("summary")))return JSONObject().put("status","not_captured")
        CompletionEvidence.conclude(messages,JSONObject().put("action","finish").put("outcome","completed").put("summary",conclusion.getString("summary")).put("evidence",refs))
        val key=MemoryPolicy.hash(scope+state.optString("request")+refs.toString()).take(32)
        val file=File(folder(scope),"$key.json")
        if(!file.exists()) {
            val all=CompletionEvidence.results(messages).associateBy { it.getString("tool_call_id") }
            val evidence=JSONArray((0 until refs.length()).map { i -> val ref=refs.getString(i);JSONObject().put("call_id",ref).put("result_hash",MemoryPolicy.hash(all.getValue(ref).optString("content"))) })
            val value=JSONObject().put("id",key).put("scope",scope).put("kind","task_experience").put("goal",state.optString("request").take(1200)).put("summary",conclusion.getString("summary").take(1200))
                .put("evidence",evidence).put("created_at",System.currentTimeMillis()).put("classification","agent_conclusion_with_positive_tool_results")
            atomic(file,value)
            val chatRoot=File(root,HistoryEditing.scope(scope).first)
            chatRoot.walkTopDown().filter { it.isFile && it.extension=="json" && it.name!="uses.json" }.sortedByDescending { it.lastModified() }.drop(64).forEach { check(it.delete()) { "Unable to retire old task experience." } }
        }
        return JSONObject().put("status","captured").put("experience_id",key).put("notice","Candidate evidence only; no Skill was installed and no user fact was saved.")
    }
    private fun uses(scope: String)=read(File(folder(scope),"uses.json")).let { if(!it.has("skills"))it.put("skills",JSONArray()).put("events",JSONArray()) else it }
    @Synchronized fun observe(scope: String,call: JSONObject,result: String,revisionOf: (String) -> String? = { null }) {
        val f=runCatching { ToolRegistry.function(call) }.getOrNull()?:return
        val args=runCatching { JSONObject(f.getString("arguments")) }.getOrNull()?:return
        val value=runCatching { JSONObject(result) }.getOrNull()
        val load=f.optString("name")=="skill" && args.optString("action","load")=="load" || f.optString("name")=="extensions" && args.optString("action")=="load_skill"
        if(!load && !CompletionEvidence.unsuccessful(result))return
        val state=uses(scope);val before=state.toString()
        if(load && value!=null && value.optString("id")==args.optString("id") && (value.has("body") || value.has("instructions"))) {
            val skillId=value.getString("id")
            val version=value.optString("revision").ifBlank { revisionOf(skillId).orEmpty() }
            if(version.isNotBlank()) {
                val all=state.getJSONArray("skills");val kept=(0 until all.length()).map { all.getJSONObject(it) }.filterNot { it.getString("id")==skillId }
                state.put("skills",JSONArray((kept+JSONObject().put("id",skillId).put("revision",version).put("source",value.optString("source","installed")).put("load_call",call.getString("id"))).takeLast(8)))
            }
        }
        if(CompletionEvidence.unsuccessful(result) && state.getJSONArray("skills").length()>0) event(state,JSONObject().put("kind","tool_failure_after_load").put("source_call",call.getString("id")).put("tool",f.optString("name")).put("result_hash",MemoryPolicy.hash(result)).put("causality_verified",false).put("skill_revisions",JSONArray(state.getJSONArray("skills").toString())))
        if(state.toString()!=before) {
            atomic(File(folder(scope),"uses.json"),state)
            val events=state.getJSONArray("events");if(events.length()>0 && !load)share(scope,events.getJSONObject(events.length()-1),state.getJSONArray("skills"))
        }
    }
    fun refreshLegacyDefinitions(messages: JSONArray,revisionOf: (String) -> String?) {
        val calls=mutableMapOf<String,String>();val current=mutableMapOf<String,String?>()
        for(i in 0 until messages.length()) {
            val message=messages.getJSONObject(i)
            message.optJSONArray("tool_calls")?.let { a -> for(j in 0 until a.length()) {
                val call=a.getJSONObject(j);val f=runCatching { ToolRegistry.function(call) }.getOrNull()?:continue
                val args=runCatching { JSONObject(f.optString("arguments")) }.getOrNull()
                if(f.optString("name")=="extensions" && args?.optString("action")=="load_skill")calls[call.getString("id")]=args.optString("id")
            } }
            if(message.optString("role")!="tool")continue
            val id=calls[message.optString("tool_call_id")]?:continue
            val value=runCatching { JSONObject(message.optString("content")) }.getOrNull()?:continue
            if(!value.has("instructions"))continue
            val version=if(current.containsKey(id))current[id] else runCatching { revisionOf(id) }.getOrNull().also { current[id]=it }
            if(value.optString("revision").isBlank() || version==null || version!=value.optString("revision"))
                message.put("content",JSONObject().put("id",id).put("status","skill_context_unavailable").put("notice","Legacy Skill content is unversioned, changed, disabled or unavailable. Use the current Skill loading interface before following its instructions; do not replay old resources.").toString())
        }
    }
    @Synchronized fun observeVisible(scope: String,messages: JSONArray) {
        val found=(0 until messages.length()).mapNotNull { i ->
            val text=messages.getJSONObject(i).optString("content")
            val obj=runCatching { if(text.startsWith("[Capri Skill"))JSONObject(text.substringAfter('\n').substringBefore("\nEnd of Skill snapshot.")) else JSONObject(text) }.getOrNull()
            obj?.takeIf { it.has("body") && it.has("resource_base") && it.optString("revision").matches(Regex("[a-f0-9]{64}")) && it.optString("id").matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")) }
        }
        if(found.isEmpty())return
        val state=uses(scope);val before=state.toString();var all=(0 until state.getJSONArray("skills").length()).map { state.getJSONArray("skills").getJSONObject(it) }
        found.forEach { snapshot ->
            if(all.none { it.optString("id")==snapshot.getString("id") && it.optString("revision")==snapshot.getString("revision") && it.optString("source")==snapshot.optString("source") })
                all=all.filterNot { it.optString("id")==snapshot.getString("id") } + JSONObject().put("id",snapshot.getString("id")).put("revision",snapshot.getString("revision")).put("source",snapshot.optString("source")).put("load_kind","visible_definition")
        }
        state.put("skills",JSONArray(all.takeLast(8)));if(state.toString()!=before)atomic(File(folder(scope),"uses.json"),state)
    }
    private fun event(state: JSONObject,value: JSONObject) { val all=state.getJSONArray("events");val key=MemoryPolicy.hash(value.toString());val existing=(0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.optString("event_id")==key };if(existing!=null) { value.put("event_id",key).put("at",existing.getLong("at"));return }
        value.put("event_id",key).put("at",System.currentTimeMillis());state.put("events",JSONArray(((0 until all.length()).map { all.getJSONObject(it) }+value).takeLast(32))) }
    @Synchronized fun execute(scope: String,entries: List<Entry>,messages: JSONArray,args: JSONObject,store: SkillHistory,files: WorkspaceFileTools,checkCancelled: () -> Unit = {}): String {
        TaskExperienceContract.validate(args);val action=args.getString("action");val (chat,_,_)=HistoryEditing.scope(scope)
        checkCancelled()
        return when(action) {
            "list" -> {
                val retained=folder(scope).listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{32}\\.json")) }.sortedByDescending { it.lastModified() }
                val offset=args.optInt("offset",0);val feedback=uses(scope);val events=feedback.getJSONArray("events")
                feedback.put("events",JSONArray((maxOf(0,events.length()-2) until events.length()).map { events.getJSONObject(it) })).put("event_count",events.length()).put("events_omitted",maxOf(0,events.length()-2))
                JSONObject().put("status","ok").put("experiences",JSONArray(retained.drop(offset).take(8).map { read(it).apply { remove("goal");remove("evidence");put("summary",optString("summary").take(240)) } }))
                    .put("total",retained.size).put("next_offset",if(offset+8<retained.size) offset+8 else JSONObject.NULL).put("skill_feedback",feedback).toString()
            }
            "feedback_history" -> synchronized(sharedLock) {
                val skill=ai.capri.app.extensions.ExtensionPolicy.id(args.getString("skill_id"));val local=args.optString("scope","skill_metadata")=="current_task"
                val source=if(local)uses(scope).getJSONArray("events") else read(File(root.parentFile,"skill-feedback/$skill.json")).optJSONArray("events")?:JSONArray()
                val retained=(0 until source.length()).map { source.getJSONObject(it) }.filter { event ->
                    !local || event.optString("skill_id")==skill || event.optJSONArray("skill_revisions")?.let { a -> (0 until a.length()).any { a.getJSONObject(it).optString("id")==skill } }==true
                }.asReversed()
                val offset=args.optInt("offset",0);val size=if(local)4 else 8
                val page=retained.drop(offset).take(size).map { event -> JSONObject(event.toString()).apply {
                    optJSONArray("skill_revisions")?.let { a -> put("skill_revisions",JSONArray((0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("id")==skill })) }
                } }
                JSONObject().put("status","ok").put("scope",if(local)"current_task" else "sanitized_skill_metadata").put("events",JSONArray(page)).put("total",retained.size)
                    .put("next_offset",if(offset+size<retained.size)offset+size else JSONObject.NULL).put("retention_limit",32)
                    .put("notice","Source text is task-scoped. Loading or temporal association does not establish use, causality or success.").toString()
            }
            "read" -> read(File(folder(scope),"${id(args.getString("experience_id"))}.json")).also { require(it.has("id")) { "Experience not found in this task revision." } }.toString()
            "draft" -> {
                val experience=read(File(folder(scope),"${id(args.getString("experience_id"))}.json"));require(experience.has("evidence")) { "A completed task with recorded positive evidence is required." }
                val name=ai.capri.app.extensions.ExtensionPolicy.id(args.getString("skill_id"))
                require(creatorLoaded(messages)) { "Load skill-creator through the current Skill loading interface before authoring a draft." }
                fun english(field: String): String = args.getString(field).also { require(it.isNotBlank() && it.length<=1600 && it.all { c -> c=='\n' || c=='\t' || c.code in 32..126 } && !MemoryPolicy.credentialValue(it)) { "$field must be concise English authoring text without credentials." } }
                val description=english("description");require(description.length<=1024)
                val text="---\nname: $name\ndescription: ${JSONObject.quote(description)}\nmetadata:\n  capri/experience: ${experience.getString("id")}\n---\n\n"+
                    "## When to use\n\n${english("triggers")}\n\n## Procedure\n\n${english("steps")}\n\n## Validation\n\n${english("validation")}\n\n## Failure branches\n\n${english("failures")}\n\nUse current environment evidence and existing tool permissions. Historical success does not guarantee the current task succeeds.\n"
                val path="skill-drafts/$name/SKILL.md";val target=workspace.file(chat,path)
                require(!target.exists() || target.readText()==text) { "Draft differs from the existing workspace file; preserve user edits and choose another Skill ID." }
                val result=if(target.exists())JSONObject().put("status","ok").put("unchanged",true) else JSONObject(files.executeScoped(chat,scope,"write_file",JSONObject().put("path",path).put("content",text),checkCancelled=checkCancelled))
                result.put("experience_id",experience.getString("id")).put("path",path).put("installed",false).put("next","Review and test the draft, then use the existing extensions.install_skill importer. Do not infer execution success from import.").toString()
            }
            "feedback" -> {
                val state=uses(scope);val skills=state.getJSONArray("skills");val skill=args.getString("skill_id")
                require((0 until skills.length()).any { skills.getJSONObject(it).getString("id")==skill } || visibleSkill(messages,skill)) { "Feedback requires a Skill observed in the current task context." }
                val observed=(0 until skills.length()).map { skills.getJSONObject(it) }.lastOrNull { it.getString("id")==skill }
                val kind=args.getString("kind");val text=args.getString("text");require(text.isNotBlank() && text.length<=800 && !MemoryPolicy.credentialValue(text)) { "Feedback text must be bounded and credential-free." }
                val value=JSONObject().put("kind",kind).put("skill_id",skill).put("text",text).put("causality_verified",false).put("loaded_revision",observed?.optString("revision") ?: "visible_context_only").put("source",observed?.optString("source") ?: "visible_context")
                if(kind=="user_correction") {
                    val entry=entries.getOrNull(args.getInt("source_entry")-1);require(entry?.role=="user" && entry.text.contains(args.getString("excerpt")) && args.getString("excerpt").isNotBlank()) { "User correction requires an exact excerpt from the referenced user message." }
                    value.put("entry_id",entry!!.id).put("source_hash",MemoryPolicy.hash(entry.text))
                } else {
                    val ref=args.getString("source_call");val result=(0 until messages.length()).map { messages.getJSONObject(it) }.firstOrNull { it.optString("role")=="tool" && it.optString("tool_call_id")==ref }?:error("Feedback source call was not recorded in this task.")
                    require(CompletionEvidence.unsuccessful(result.optString("content"))) { "Failure/dependency feedback requires an actual unsuccessful result." };value.put("source_call",ref).put("result_hash",MemoryPolicy.hash(result.optString("content")))
                }
                event(state,value);atomic(File(folder(scope),"uses.json"),state);val shared=runCatching { share(scope,value,JSONArray().apply { observed?.let(::put) }) }.isSuccess
                JSONObject().put("status","recorded").put("shared_index_available",shared).put("notice","Feedback does not prove the Skill caused the failure and does not modify its instructions.").toString()
            }
            "revisions" -> JSONObject().put("status","ok").put("revisions",store.skillVersions(args.getString("skill_id"))).toString()
            "compare" -> store.compareSkill(args.getString("skill_id"),args.getString("before"),args.getString("after")).put("status","ok").toString()
            "restore" -> JSONObject().put("status","ok").put("restored",true).put("skill_id",store.restoreSkill(args.getString("skill_id"),args.getString("revision"),args.getString("expected_revision"))).put("notice","Historical content restored; enablement and permissions remain unchanged. No resource was executed. Reload through the current Skill interface before use.").toString()
            else -> error("Unknown task_experience action.")
        }
    }
    private fun visibleSkill(messages: JSONArray,id: String): Boolean = (0 until messages.length()).any { i -> val text=messages.getJSONObject(i).optString("content");val value=runCatching { if(text.startsWith("[Capri Skill"))JSONObject(text.substringAfter('\n').substringBefore("\nEnd of Skill snapshot.")) else JSONObject(text) }.getOrNull();value?.optString("id")==id && (value.has("body") || value.has("instructions")) }
    private fun creatorLoaded(messages: JSONArray)=visibleSkill(messages,"skill-creator")
}
