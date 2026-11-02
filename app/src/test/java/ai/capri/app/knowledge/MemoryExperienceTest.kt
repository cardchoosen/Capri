package ai.capri.app.knowledge

import ai.capri.app.*
import ai.capri.app.agent.*
import ai.capri.app.extensions.*
import ai.capri.app.files.WorkspaceFileTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class MemoryExperienceTest {
    @get:Rule val temp=TemporaryFolder()
    private val workspace get()=Workspace(File(temp.root,"sessions"))
    private fun store()=KnowledgeStore(File(temp.root,"knowledge").apply { mkdirs() },workspace,{it.readText()},{file,text->file.writeText(text)})
    private fun personal()=Session(id="personal",kind=SessionKind.PERSONAL,messages=mutableListOf(Entry("user","Prefer tea",timestamp=10)))
    private fun remember(text: String="Prefers tea",source: Int=1)=JSONObject().put("action","remember").put("text",text).put("source_entry",source).put("key","drink.preference").put("category","preference")
    private val port=object:SkillHistory {
        override fun skillVersions(id:String)=JSONArray()
        override fun compareSkill(id:String,before:String,after:String)=JSONObject()
        override fun restoreSkill(id:String,revision:String,expected:String)=error("No installed Skill in fixture")
    }
    private fun call(id:String,name:String,args:JSONObject=JSONObject())=JSONObject().put("id",id).put("type","function").put("function",JSONObject().put("name",name).put("arguments",args.toString()))
    private fun completed():JSONArray {
        val messages=JSONArray().put(JSONObject().put("role","system").put("content","rules"))
        TaskState.ensure(messages,"Build and validate a reusable example",false)
        messages.put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(call("verified","python_run"))))
            .put(JSONObject().put("role","tool").put("tool_call_id","verified").put("content","{\"status\":\"ok\",\"exit_code\":0,\"output\":\"sensitive output not copied\"}"))
        TaskState.execute(messages,JSONObject().put("action","finish").put("outcome","completed").put("summary","Built and checked the example").put("evidence",JSONArray(listOf("verified"))))
        return messages
    }
    @Test fun topicCorrectionsRevokeOldNotesAndStaleSourcesCannotOverwriteThem() {
        val memory=PersonalMemory(store());val chat=personal()
        val a=JSONObject(memory.execute(chat,chat,remember()))
        chat.messages+=Entry("user","Prefer coffee now",timestamp=20)
        val b=JSONObject(memory.execute(chat,chat,remember("Prefers coffee",2)))
        assertEquals(a.getString("id"),b.getString("id"));assertEquals(2,b.getInt("revision"))
        assertFalse(memory.recall(chat,"drink").contains("Prefers tea"))
        assertThrows(IllegalArgumentException::class.java) { memory.execute(chat,chat,remember("Prefers tea",1)) }
        assertThrows(IllegalArgumentException::class.java) { memory.execute(chat,chat,remember("New value",2).put("expected_revision",1)) }
        assertThrows(IllegalArgumentException::class.java) { memory.execute(chat,chat,remember().put("category","environment_fact")) }
    }
    @Test fun expiryForgetAndChangedSourceTextStopRecallAndCachedRetrieval() {
        val backing=store();val memory=PersonalMemory(backing);val chat=personal()
        val id=JSONObject(memory.execute(chat,chat,remember())).getString("id")
        val note=backing.personalRecords(chat.id).single();backing.savePersonal(note.put("expires_at",System.currentTimeMillis()-1))
        assertTrue(memory.recall(chat,"tea").isBlank())
        assertThrows(IllegalArgumentException::class.java) { memory.execute(chat,chat,remember()) }
        chat.messages+=Entry("user","Prefer tea again",timestamp=20)
        memory.execute(chat,chat,remember(source=2))
        chat.messages[1]=chat.messages[1].copy(text="Changed in place")
        assertTrue(memory.recall(chat,"tea").isBlank())
        chat.messages[1]=chat.messages[1].copy(text="Prefer tea again")
        val messages=JSONArray().put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(call("lookup","personal_memory",JSONObject().put("action","search").put("query","tea")))))
            .put(JSONObject().put("role","tool").put("tool_call_id","lookup").put("content","old secret summary"))
        memory.execute(chat,chat,JSONObject().put("action","forget").put("id",id))
        MemoryContext(memory).refresh(chat,chat,listOf(chat),messages)
        assertEquals("[]",messages.getJSONObject(1).getString("content"))
        assertTrue(memory.recall(chat,"tea").isBlank())
        assertThrows(IllegalArgumentException::class.java) { memory.execute(chat,chat,remember()) }
        chat.messages+=Entry("user","Please remember tea again",timestamp=30)
        memory.execute(chat,chat,remember(source=3))
        assertTrue(memory.recall(chat,"tea").contains("Prefers tea"))
        assertThrows(IllegalArgumentException::class.java) { memory.execute(chat,Session(),JSONObject().put("action","forget").put("id",id)) }
    }
    @Test fun forgottenOrSupersededFactsCannotReturnThroughAutomaticEpisodes() {
        val memory=PersonalMemory(store());val chat=personal()
        chat.messages+=Entry("assistant","Tea preference noted",finalReply=true,timestamp=11);memory.recordTurn(chat)
        val note=JSONObject(memory.execute(chat,chat,remember())).getString("id")
        store().savePersonal(store().personalRecords(chat.id).first { it.getString("id")==note }.put("expires_at",System.currentTimeMillis()-1))
        assertTrue(memory.recall(chat,"tea").isBlank())
        memory.execute(chat,chat,JSONObject().put("action","forget").put("id",note))
        assertTrue(memory.recall(chat,"tea").isBlank())
        memory.recordTurn(chat);assertTrue(memory.recall(chat,"tea").isBlank())
        assertTrue(ConversationHistory.execute(listOf(chat),JSONObject().put("action","search").put("query","tea")).contains("Prefer tea"))
        chat.messages+=Entry("user","Prefer coffee now",timestamp=20)
        memory.execute(chat,chat,remember("Prefers coffee",3))
        assertFalse(memory.recall(chat,"tea").contains("Tea preference noted"))
    }
    @Test fun storageQuotaIsAtomicAndForgettingFreesCuratedCapacity() {
        val backing=store();val memory=PersonalMemory(backing);val chat=personal()
        repeat(20) { i -> memory.execute(chat,chat,remember("x".repeat(1600)).put("key","topic.$i")) }
        assertThrows(IllegalArgumentException::class.java) { memory.execute(chat,chat,remember("overflow").put("key","topic.extra")) }
        assertEquals(20,backing.personalRecords(chat.id).size)
        val id=backing.personalRecords(chat.id).first().getString("id")
        memory.execute(chat,chat,JSONObject().put("action","forget").put("id",id))
        memory.execute(chat,chat,remember("replacement").put("key","topic.extra"))
        assertEquals(20,memory.active(chat.id,chat).size)
        chat.messages.clear();chat.messages+=Entry("user","New timeline preference",timestamp=100)
        memory.execute(chat,chat,remember("New sourced note").put("key","new.preference"))
        assertEquals(1,memory.active(chat.id,chat).size)
        assertTrue(backing.personalRecords(chat.id).any { it.optString("state")=="source_revoked" })
    }
    @Test fun stableSnapshotDoesNotChurnWithQueryButCurrentChangesReplaceIt() {
        val memory=PersonalMemory(store());val chat=personal();memory.execute(chat,chat,remember())
        val context=MemoryContext(memory)
        val a=context.messages(chat,chat,listOf(chat),"tea",4200);val b=context.messages(chat,chat,listOf(chat),"music",4200)
        assertEquals(a[0].toString(),b[0].toString())
        chat.messages+=Entry("user","Prefer coffee",timestamp=20);memory.execute(chat,chat,remember("Prefers coffee",2))
        val c=context.messages(chat,chat,listOf(chat),"tea",4200)
        assertNotEquals(a[0].toString(),c[0].toString());assertFalse(c.toString().contains("Prefers tea"))
        assertTrue(context.messages(chat,Session(id="task"),listOf(chat),"tea",4200).none { it.toString().contains("Prefers coffee") })
    }
    @Test fun memoryContextCostIsMeasuredWithoutAnExtraModelCall() {
        val memory=PersonalMemory(store());val chat=personal()
        repeat(4) { index -> memory.execute(chat,chat,remember("Preference $index: tea").put("key","preference.$index")) }
        val context=MemoryContext(memory)
        val firstAt=System.nanoTime();val first=context.messages(chat,chat,listOf(chat),"tea",4200);val firstMs=(System.nanoTime()-firstAt)/1_000_000.0
        val nextAt=System.nanoTime();val next=context.messages(chat,chat,listOf(chat),"music",4200);val nextMs=(System.nanoTime()-nextAt)/1_000_000.0
        assertEquals(first.first().getString("content"),next.first().getString("content"))
        val tokens=ai.capri.app.model.ContextTokens.estimate(JSONArray(first),JSONArray())
        println("MEMORY_CONTEXT_METRICS "+JSONObject().put("fixture_notes",4).put("first_ms",firstMs).put("next_ms",nextMs)
            .put("estimated_context_tokens",tokens).put("stable_chars",first.first().getString("content").length).put("total_chars",first.sumOf { it.getString("content").length }).put("stable_prefix_equal",true).put("model_calls",0))
        assertTrue(first.sumOf { it.getString("content").length }<6000)
    }
    @Test fun explicitPastQuestionsRetrieveCitableCurrentTimelinesAndStableIds() {
        val memory=PersonalMemory(store());val current=Session(id="task",messages=mutableListOf(Entry("user","上次编译问题怎么解决的")))
        val old=Session(id="old",messages=mutableListOf(Entry("user","编译问题使用指定编译器解决"),Entry("assistant","我声称构建成功")))
        val context=MemoryContext(memory)
        val result=context.history(listOf(old,current),current,current.messages[0].text).getString("content")
        assertTrue(result.contains(old.messages[0].id));assertTrue(result.contains("sha256"));assertFalse(result.contains(current.messages[0].id))
        assertTrue(context.history(listOf(old,current),current,"写一段代码").getString("content").contains("false"))
        val request=JSONObject().put("action","read").put("chat_id",old.id).put("entry_id",old.messages[0].id)
        old.messages.add(0,Entry("user","Inserted earlier"))
        assertTrue(ConversationHistory.execute(listOf(old),request).contains("编译问题"))
        old.messages.removeAt(1)
        assertThrows(IllegalStateException::class.java) { ConversationHistory.execute(listOf(old),request) }
    }
    @Test fun onlyPositiveEvidenceCreatesAnIdempotentScopedExperience() {
        val experience=TaskExperience(File(temp.root,"experience"),workspace);val messages=completed()
        val a=experience.capture("chat",messages);val b=experience.capture("chat",messages)
        assertEquals(a.getString("experience_id"),b.getString("experience_id"))
        val args=JSONObject().put("action","read").put("experience_id",a.getString("experience_id"))
        val result=experience.execute("chat",emptyList(),messages,args,port,WorkspaceFileTools(workspace))
        assertTrue(result.contains("verified"));assertFalse(result.contains("sensitive output not copied"))
        assertThrows(IllegalArgumentException::class.java) { experience.execute("other",emptyList(),messages,args,port,WorkspaceFileTools(workspace)) }
        messages.getJSONObject(2).put("content","{\"status\":\"failed\"}")
        assertThrows(IllegalArgumentException::class.java) { experience.capture("chat",messages) }
    }
    @Test fun draftIsEnglishReviewableRuleGuardedAndNeverInstalls() {
        val experience=TaskExperience(File(temp.root,"experience"),workspace);val messages=completed();val id=experience.capture("chat",messages).getString("experience_id")
        val args=JSONObject().put("action","draft").put("experience_id",id).put("skill_id","example-method").put("description","Build a checked example when requested")
            .put("triggers","Use for a matching example build.").put("steps","1. Inspect inputs.\n2. Build using declared dependencies.").put("validation","Check the real artifact and exit code.").put("failures","Stop on failure; inspect partial changes before retrying.")
        val files=WorkspaceFileTools(workspace)
        assertThrows(IllegalArgumentException::class.java) { experience.execute("chat",emptyList(),messages,args,port,files) }
        messages.put(JSONObject().put("role","tool").put("content",JSONObject().put("id","skill-creator").put("body","Author English Skills").toString()))
        val result=JSONObject(experience.execute("chat",emptyList(),messages,args,port,files))
        assertFalse(result.getBoolean("installed"));assertEquals("ok",result.getString("status"))
        val file=workspace.file("chat",result.getString("path"));assertTrue(file.readText().contains("## Failure branches"));assertTrue(file.readText().contains(id))
        assertThrows(IllegalArgumentException::class.java) { experience.execute("chat",emptyList(),messages,JSONObject(args.toString()).put("triggers","中文指令"),port,files) }
        file.appendText("User edit")
        assertThrows(IllegalArgumentException::class.java) { experience.execute("chat",emptyList(),messages,args,port,files) }
        val guarded=JSONObject(args.toString()).put("skill_id","blocked-method")
        workspace.file("chat","AGENTS.md").writeText("Review project conventions before writes.")
        assertEquals("project_rules_required",JSONObject(experience.execute("chat",emptyList(),messages,guarded,port,files)).getString("status"))
    }
    @Test fun feedbackRequiresActualFailureOrExactUserSourceAndRecordsNoCausalityClaim() {
        val experience=TaskExperience(File(temp.root,"experience"),workspace);val messages=completed();val files=WorkspaceFileTools(workspace)
        experience.observe("chat",call("load","extensions",JSONObject().put("action","load_skill").put("id","example")),JSONObject().put("id","example").put("instructions","Use a compiler").toString()) { "a".repeat(64) }
        val failure="{\"status\":\"failed\"}";messages.put(JSONObject().put("role","tool").put("tool_call_id","build").put("content",failure))
        experience.observe("chat",call("build","python_run"),failure)
        val args=JSONObject().put("action","feedback").put("skill_id","example").put("kind","failure").put("text","The compiler invocation failed").put("source_call","build")
        assertEquals("recorded",JSONObject(experience.execute("chat",emptyList(),messages,args,port,files)).getString("status"))
        assertThrows(IllegalArgumentException::class.java) { experience.execute("chat",emptyList(),messages,JSONObject(args.toString()).put("source_call","verified"),port,files) }
        val correction=JSONObject().put("action","feedback").put("skill_id","example").put("kind","user_correction").put("text","Use the requested compiler").put("source_entry",1).put("excerpt","use clang")
        assertThrows(IllegalArgumentException::class.java) { experience.execute("chat",listOf(Entry("user","Other message")),messages,correction,port,files) }
        experience.execute("chat",listOf(Entry("user","Please use clang")),messages,correction,port,files)
        val list=experience.execute("chat",emptyList(),messages,JSONObject().put("action","list"),port,files)
        assertTrue(list.contains("causality_verified\":false"));assertTrue(list.contains("a".repeat(64)))
        val cross=experience.execute("other",emptyList(),JSONArray(),JSONObject().put("action","feedback_history").put("skill_id","example"),port,files)
        assertTrue(cross.contains("a".repeat(64)));assertFalse(cross.contains("The compiler invocation failed"));assertFalse(cross.contains("Please use clang"))
        TaskExperience.deleteSharedFeedback(temp.root,"chat")
        assertFalse(experience.execute("other",emptyList(),JSONArray(),JSONObject().put("action","feedback_history").put("skill_id","example"),port,files).contains("a".repeat(64)))
    }
    @Test fun explicitProgressiveSkillSnapshotsFeedTheSameFeedbackLifecycle() {
        val experience=TaskExperience(File(temp.root,"experience"),workspace);val snapshot=JSONObject().put("id","review").put("revision","b".repeat(64)).put("source","project:repo").put("body","Check changed files").put("resource_base","/workspace/.capri/skill-resources/review")
        val messages=JSONArray().put(JSONObject().put("role","user").put("content","[Capri Skill snapshot]\n$snapshot\nEnd of Skill snapshot."))
        experience.observeVisible("chat",messages)
        experience.observe("chat",call("failed","python_run"),"{\"status\":\"failed\"}")
        val result=experience.execute("chat",emptyList(),messages,JSONObject().put("action","list"),port,WorkspaceFileTools(workspace))
        assertTrue(result.contains("project:repo"));assertTrue(result.contains("b".repeat(64)))
    }
    @Test fun legacyDefinitionsLoseInstructionAuthorityWhenTheirVersionChanges() {
        val experience=TaskExperience(File(temp.root,"experience"),workspace)
        fun history(version:String)=JSONArray().put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(call("load","extensions",JSONObject().put("action","load_skill").put("id","example")))))
            .put(JSONObject().put("role","tool").put("tool_call_id","load").put("content",JSONObject().put("id","example").put("revision",version).put("instructions","Old body").toString()))
        val same=history("a".repeat(64));experience.refreshLegacyDefinitions(same) { "a".repeat(64) };assertTrue(same.toString().contains("Old body"))
        val changed=history("a".repeat(64));experience.refreshLegacyDefinitions(changed) { "b".repeat(64) };assertFalse(changed.toString().contains("Old body"));assertTrue(changed.toString().contains("skill_context_unavailable"))
        val unknown=history("");experience.refreshLegacyDefinitions(unknown) { "a".repeat(64) };assertFalse(unknown.toString().contains("Old body"))
    }
    @Test fun mutatingSkillActionsCanProvideEvidenceWhileHistoryReadsCannot() {
        val messages=JSONArray().put(JSONObject().put("role","system").put("content","rules"));TaskState.ensure(messages,"Restore an installed Skill",false)
        messages.put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(call("restore","task_experience",JSONObject().put("action","restore")))))
            .put(JSONObject().put("role","tool").put("tool_call_id","restore").put("content","{\"status\":\"ok\",\"restored\":true}"))
        assertEquals(1,CompletionEvidence.results(messages).size)
        TaskState.execute(messages,JSONObject().put("action","finish").put("outcome","completed").put("summary","Restored the requested version").put("evidence",JSONArray(listOf("restore"))))
        messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments","{\"action\":\"revisions\"}")
        assertTrue(CompletionEvidence.results(messages).isEmpty())
    }
    @Test fun skillSnapshotsIncludeResourcesAndRejectStaleRestoreOrSymlinks() {
        val dir=File(temp.root,"example").apply { mkdirs() };File(dir,"SKILL.md").writeText("---\nname: example\ndescription: Test\n---\nCheck it\n")
        val resource=File(dir,"helper.py").apply { writeText("print(1)\n") }
        val parsed=SkillParser.parse(dir)
        parsed.javaClass.methods.firstOrNull { it.name=="revision" && it.parameterCount==0 }?.let { assertEquals(SkillVersions.revision(dir),it.invoke(parsed)) }
        val versions=SkillVersions(File(temp.root,"versions"));val a=versions.capture("example",dir,"installed")
        resource.writeText("print(2)\n");val b=versions.capture("example",dir,"validated_import",a)
        assertNotEquals(a,b);assertEquals(2,versions.list("example",b).length())
        assertEquals("helper.py",versions.compare("example",a,b).getJSONArray("files").getJSONObject(0).getString("path"))
        assertEquals("print(1)\n",File(versions.source("example",a),"helper.py").readText())
        assertThrows(IllegalArgumentException::class.java) { SkillVersions.requireExpected(dir,a) }
        SkillVersions.requireExpected(dir,b)
        Files.createSymbolicLink(File(dir,"unsafe").toPath(),resource.toPath())
        assertThrows(IllegalArgumentException::class.java) { versions.capture("example",dir,"invalid") }
    }
    @Test fun feedbackSchemaDiscoveryAndMemoryOwnershipAreClosed() {
        TaskExperienceContract.validate(JSONObject().put("action","list"))
        assertThrows(IllegalArgumentException::class.java) { TaskExperienceContract.validate(JSONObject().put("action","restore").put("skill_id","example")) }
        assertThrows(IllegalArgumentException::class.java) { TaskExperienceContract.validate(JSONObject().put("action","feedback").put("skill_id","example").put("kind","user_correction").put("text","Correction").put("source_call","invented")) }
        val registry=ToolRegistry(JSONArray().put(TaskExperienceContract.specification()));assertTrue(registry.definitions.getValue("task_experience").deferred)
        assertTrue(CapabilitySearch.score(listOf("技能反馈"),"task_experience",registry.definitions.getValue("task_experience").description)>0)
        assertThrows(IllegalArgumentException::class.java) { store().putMemory("chat","Installed compiler","source",category="environment_fact") }
    }
}
