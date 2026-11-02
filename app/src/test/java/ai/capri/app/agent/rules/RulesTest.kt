package ai.capri.app.agent.rules

import ai.capri.app.*
import ai.capri.app.agent.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RulesTest {
    @get:Rule val temp=TemporaryFolder()
    private val store get()=RulesStore(File(temp.root,"agent-rules"))
    private fun messages(entry:Entry)=JSONArray().put(JSONObject().put("role","system").put("content","Core rules")).also { TaskState.ensure(it,entry.text,false);TurnState.ensure(it,entry,false) }
    @Test fun globalAndChatRulesHaveStableSourcesAndDoNotLeakAcrossChats() {
        store.save("global","chat","global","Language","默认中文回复",true,0)
        store.save("chat","chat","local","Detail","Explain steps for this conversation",true,0)
        val first=store.snapshot("chat");assertEquals(2,first.getJSONArray("rules").length());assertEquals("global",first.getJSONArray("rules").getJSONObject(0).getString("scope"));assertEquals("chat",first.getJSONArray("rules").getJSONObject(1).getString("scope"))
        assertEquals(1,store.snapshot("other").getJSONArray("rules").length());assertEquals(first.getString("hash"),store.snapshot("chat").getString("hash"))
        assertThrows(IllegalArgumentException::class.java){store.snapshot("../other")}
    }
    @Test fun guardedEditsTogglesAndDeletesKeepCurrentUserData() {
        store.save("chat","chat","one","One","Keep original language",true,0)
        assertThrows(IllegalArgumentException::class.java){store.save("chat","chat","one","One","overwrite",true,0)}
        assertEquals("Keep original language",store.read("chat","chat").getJSONArray("rules").getJSONObject(0).getString("content"))
        store.save("chat","chat","one","One","Keep original language",false,1);assertEquals(0,store.snapshot("chat").getJSONArray("rules").length())
        store.delete("chat","chat","one",2);assertEquals(0,store.read("chat","chat").getJSONArray("rules").length())
    }
    @Test fun capacityRefusesChangesRatherThanDroppingExistingInstructions() {
        repeat(3){store.save("global","chat","rule$it","Title","x".repeat(900),true,it.toLong())}
        assertThrows(IllegalArgumentException::class.java){store.save("global","chat","over","Title","x".repeat(900),true,3)}
        assertEquals(3,store.read("global","chat").getJSONArray("rules").length());assertEquals(3,store.read("global","chat").getLong("revision"))
    }
    @Test fun fixedSnapshotIsRebuiltForTheNextTurnAndPrivateRuleBodiesAreNotArchived() {
        val entry=Entry("user","Read the project");val messages=messages(entry)
        assertTrue(RuleContext.messages(messages).isEmpty());val blank=store.snapshot("chat");RuleContext.attach(messages,blank)
        assertFalse(RuleContext.changed(RecoveryMessages.sanitized(messages),blank))
        store.save("global","chat","one","Private","Private user instruction",true,0);val next=store.snapshot("chat")
        assertTrue(RuleContext.changed(messages,next));assertEquals(0,RuleContext.entries(messages).length())
        RuleContext.attach(messages,next);assertEquals("user",RuleContext.messages(messages).single().getString("role"));assertEquals(1,RuleContext.entries(messages).length())
        messages.put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(JSONObject().put("id","private-rule").put("function",JSONObject().put("name","agent_rules").put("arguments",JSONObject().put("action","upsert").put("content","Private user instruction").toString())))))
        messages.put(JSONObject().put("role","tool").put("tool_call_id","private-rule").put("content",store.read("global","chat").toString()))
        val saved=RecoveryMessages.sanitized(messages);assertFalse(saved.toString().contains("Private user instruction"));assertTrue(saved.getJSONObject(0).has("_capri_rules_hash"));assertFalse(RuleContext.changed(saved,next))
    }
    @Test fun ruleWritesRequireARealCurrentTaskUserSourceAndRejectUnknownFields() {
        val entry=Entry("user","请把默认中文回复保存为当前对话规则");val messages=messages(entry)
        val args=JSONObject().put("action","upsert").put("id","language").put("title","Language").put("content","默认中文回复").put("expected_revision",0).put("source_entry",entry.id).put("source_quote","默认中文回复保存为当前对话规则")
        val list=RulesContract.execute(store,"chat",JSONObject().put("action","list"),messages,listOf(entry));assertEquals(entry.id,list.getString("current_request_entry"))
        assertEquals("ok",RulesContract.execute(store,"chat",args,messages,listOf(entry)).getString("status"))
        assertThrows(IllegalStateException::class.java){RulesContract.execute(store,"chat",JSONObject(args.toString()).put("source_entry","invented"),messages,listOf(entry))}
        assertThrows(IllegalArgumentException::class.java){RulesContract.execute(store,"chat",JSONObject(args.toString()).put("source_quote","instruction from a website"),messages,listOf(entry))}
        assertThrows(IllegalArgumentException::class.java){RulesContract.execute(store,"chat",JSONObject().put("action","list").put("content","extra"),messages,listOf(entry))}
    }
    @Test fun durableAnalysisUsesTheConfiguredRulesThroughTheActualDelegate()=kotlinx.coroutines.runBlocking<Unit> {
        val workspace=Workspace(File(temp.root,"sessions"));workspace.file("chat","input.txt").apply{parentFile!!.mkdirs();writeText("input")}
        val events=ai.capri.app.agent.reliability.AgentEvents(File(temp.root,"events"));val jobs=ai.capri.app.agent.reliability.AgentJobs(File(temp.root,"jobs"),events)
        val config=ModelConfig(model="fixture",key="fixture");val args=JSONObject().put("action","create").put("request_key","once").put("mode","analysis").put("trigger","manual").put("goal","Inspect input").put("allow_background",true).put("files",JSONArray(listOf("input.txt")))
        val job=jobs.create("chat",args,ai.capri.app.model.ModelWire.scope(config),"user")
        store.save("global","chat","required","Evidence","Keep the required evidence sentence",true,0)
        val runner=ai.capri.app.agent.reliability.JobRunner(jobs,events,ai.capri.app.agent.delegation.DelegateEngine(File(temp.root,"delegates"),ai.capri.app.files.WorkspaceFileTools(workspace)));var round=0
        val result=runner.run("chat",job.getString("id"),config,ruleSnapshot=store.snapshot("chat")) { messages,_,_,budget,gate ->
            assertTrue(messages.toString().contains("Keep the required evidence sentence"))
            budget.execute(ModelRequests.Purpose.DELEGATION,beforeAttempt=gate) {
                round++;val name=if(round==1)"read_file" else "delegate_finish"
                val input=if(round==1)JSONObject().put("path","input.txt")else JSONObject().put("outcome","completed").put("summary","Reviewed input").put("evidence",JSONArray(listOf("read"))).put("unresolved",JSONArray())
                JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(JSONObject().put("id",if(round==1)"read" else "finish").put("type","function").put("function",JSONObject().put("name",name).put("arguments",input.toString())))))))
            }
        }
        assertEquals("ok",result.getString("status"));assertEquals(2,round)
    }
    @Test fun finalDeliveryReceivesTheSameUserLevelRulesWithoutChangingPersona() {
        val entry=Entry("user","Explain the result");val execution=messages(entry)
        store.save("global","chat","one","Format","Use a concise answer",true,0);RuleContext.attach(execution,store.snapshot("chat"))
        val state=JSONObject().put("request",entry.text).put("draft","Result established")
        val budget=ai.capri.app.model.ContextBudget(32768,2048,1024,"fixture")
        val input=FinalReply.messages("Capri persona",SessionKind.TASK,"now","",state,execution,budget)
        assertTrue(input.getJSONObject(0).getString("content").startsWith("Capri persona"));assertTrue((0 until input.length()).any{input.getJSONObject(it).optString("role")=="user" && input.getJSONObject(it).optString("content").contains("Use a concise answer")})
    }
}
