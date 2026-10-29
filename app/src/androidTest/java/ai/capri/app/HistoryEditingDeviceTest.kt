package ai.capri.app

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HistoryEditingDeviceTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private fun find(node:AccessibilityNodeInfo?,match:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo?{if(node==null)return null;if(match(node))return node;for(i in 0 until node.childCount)find(node.getChild(i),match)?.let{return it};return null}
    private fun ui(label:String):AccessibilityNodeInfo{repeat(60){ins.uiAutomation.clearCache();find(ins.uiAutomation.rootInActiveWindow){it.text?.toString()==label||it.contentDescription?.toString()==label}?.let{return it};Thread.sleep(50)};error("Missing UI: $label")}
    private fun click(node:AccessibilityNodeInfo){var n=node;while(!n.isClickable&&n.parent!=null)n=n.parent;check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
    @Test fun inlineEditorCancelSendAndRetryUseOneTimelineAndKeepExecutionState() {
        val scenario=ActivityScenario.launch(MainActivity::class.java);lateinit var vm:CapriModel
        scenario.onActivity{vm=ViewModelProvider(it)[CapriModel::class.java]};val selected=vm.selected
        val chat=Session(title="原位编辑隔离验收",messages=mutableListOf(Entry("user","你是谁"),Entry("assistant",IdentityReply.TEXT,finalReply=true),Entry("user","later"),Entry("assistant","FUTURE_MARKER",finalReply=true)))
        fun call(method:String,vararg fields:Pair<String,Any>):JSONObject{lateinit var out:JSONObject;ins.runOnMainSync{out=DebugWebBridge.call(vm,method,Bundle().apply{putString("epoch",DebugWebBridge.epoch);putString("chat_id",chat.id);for((k,v)in fields)when(v){is String->putString(k,v);is Long->putLong(k,v)}})};return out}
        try {
            ins.runOnMainSync{vm.sessions.add(0,chat);vm.select(chat.id);vm.activeTab=0;vm.persist()}
            kotlinx.coroutines.runBlocking{vm.runtime.ensureSession(chat.id)};val keep=vm.store.workspace.file(chat.id,"keep.txt");keep.writeText("keep")
            val recovery=AgentRecovery(ins.targetContext.filesDir.resolve("agent-recovery"));recovery.save(chat.id,JSONArray().put(JSONObject().put("role","system").put("content","rules")))
            val original=chat.messages.toList();click(ui("编辑消息"));ui("取消");assertNull(find(ins.uiAutomation.rootInActiveWindow){it.text?.toString()=="从历史消息继续"})
            click(ui("取消"));assertEquals(original,chat.messages)
            click(ui("编辑消息"));ui("取消");var input:AccessibilityNodeInfo?=null
            repeat(60){ins.uiAutomation.clearCache();if(input==null){val root=ins.uiAutomation.rootInActiveWindow;val labelled=find(root){it.contentDescription?.toString()?.contains("编辑历史消息")==true};input=labelled?.let{find(it){n->n.isEditable}}?:find(root){it.isEditable&&it.isFocused}};if(input==null)Thread.sleep(50)}
            checkNotNull(input){"Inline editor missing"}
            assertTrue(input!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"介绍一下你自己")}));var send:AccessibilityNodeInfo?=null;repeat(60){ins.uiAutomation.clearCache();if(send==null)send=find(ins.uiAutomation.rootInActiveWindow){it.text?.toString()=="发送"};if(send==null)Thread.sleep(50)};click(checkNotNull(send))
            repeat(60){if(chat.historyVersion==0L)Thread.sleep(50)};assertEquals(1L,chat.historyVersion);assertEquals("介绍一下你自己",chat.messages.last{it.role=="user"}.text);val expectedCount=chat.messages.indexOfLast{it.role=="user"}+2;assertEquals(expectedCount,chat.messages.size)
            assertFalse(chat.messages.any{it.text=="FUTURE_MARKER"});assertEquals("keep",keep.readText());assertNull(recovery.load(HistoryEditing.actualContextKey(chat)))
            assertTrue(ins.targetContext.filesDir.resolve("history-backups/${chat.id}/previous.json").readText().contains("FUTURE_MARKER"))
            val state=call("web_state");assertFalse(state.has("branches"));assertFalse(state.has("branch_id"));val source=chat.contextVersion;val revision=chat.historyVersion;val id=UUID.randomUUID().toString();val entry=chat.messages.last{it.role=="user"}.id
            val first=call("web_revise","entry_id" to entry,"from_context" to source,"history_revision" to revision,"request_id" to id,"mode" to "retry")
            assertEquals(first.toString(),call("web_revise","entry_id" to entry,"from_context" to source,"history_revision" to revision,"request_id" to id,"mode" to "retry").toString());assertEquals(2L,chat.historyVersion);assertEquals(expectedCount,chat.messages.size)
            var rejected=false;try{call("web_revise","entry_id" to entry,"from_context" to source,"history_revision" to revision,"request_id" to UUID.randomUUID().toString(),"mode" to "retry")}catch(_:Exception){rejected=true};assertTrue(rejected)
            assertEquals(chat.messages.map{listOf(it.id,it.role,it.text,it.timestamp,it.finalReply)},vm.store.load().first{it.id==chat.id}.messages.map{listOf(it.id,it.role,it.text,it.timestamp,it.finalReply)})
        } finally {ins.runOnMainSync{vm.sessions.removeAll{it.id==chat.id};vm.select(selected);vm.persist()};kotlinx.coroutines.runBlocking{vm.runtime.environment.sessionLock(chat.id){SessionDeletion(ins.targetContext.filesDir).cleanup(chat.id)}};scenario.close()}
    }
    @Test fun legacyBranchesAreBackedUpAndDoNotRemainInTheLiveIndex() {
        val root=ins.targetContext.cacheDir.resolve("linear-migration-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object:android.content.ContextWrapper(ins.targetContext){override fun getFilesDir()=root}
        try{
            root.resolve("sessions.json").writeText("""[{"id":"legacy","title":"Legacy","messages":[{"role":"user","text":"selected"}],"branches":[{"id":"main"}],"branch_entries":[{"text":"HIDDEN_LEGACY_HISTORY"}]}]""")
            val store=Store(context);val all=store.load();store.save(all)
            assertEquals("selected",all.first{it.id=="legacy"}.messages.single().text);assertFalse(root.resolve("sessions.json").readText().contains("branches"))
            assertTrue(root.resolve("history-backups/legacy").listFiles()!!.single().readText().contains("HIDDEN_LEGACY_HISTORY"))
            val keep=store.workspace.file("legacy","keep.txt");keep.writeText("keep")
            SharedMemoryReset.begin(context);SharedMemoryReset.resume(context,store);assertTrue(store.load().all{it.messages.isEmpty()});assertFalse(root.resolve("history-backups").exists());assertEquals("keep",keep.readText())
        }finally{root.deleteRecursively()}
    }
}
