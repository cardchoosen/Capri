package ai.capri.app

import ai.capri.app.knowledge.ContextHistory
import ai.capri.app.knowledge.PersonalMemory
import ai.capri.app.knowledge.KnowledgeStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HistoryEditingTest {
    private fun chat()=Session(id="history-test",messages=mutableListOf(Entry("user","first",timestamp=1),Entry("assistant","answer",timestamp=2),Entry("user","later",timestamp=3),Entry("assistant","FUTURE",timestamp=4)))
    @Test fun editReplacesSuffixWithoutCreatingBranchesAndRoundTrips() {
        val s=chat();val prefix=s.messages.take(2);val old=HistoryEditing.copy(s)
        HistoryEditing.replaceFrom(s,s.messages[2].id);s.messages+=Entry("user","edited")
        assertEquals(prefix,s.messages.take(2));assertFalse(s.messages.any{it.text=="FUTURE"});assertTrue(s.historyEdited);assertEquals(1L,s.historyVersion)
        val raw=SessionCatalog.encode(listOf(s));assertFalse(raw.contains("branches"));assertFalse(raw.contains("FUTURE"))
        val restored=SessionCatalog.decode(raw).first{it.id==s.id};assertEquals(s.messages,restored.messages);assertEquals(s.contextVersion,restored.contextVersion)
        HistoryEditing.restore(s,old);assertEquals(old.messages,s.messages);assertEquals(0L,s.historyVersion)
        assertThrows(IllegalArgumentException::class.java){HistoryEditing.replaceFrom(s,s.messages[1].id)}
    }
    @Test fun legacyMessagesAndActiveProjectionKeepStableIds() {
        val raw="""[{"id":"old","title":"old","messages":[{"role":"user","text":"selected"}],"branches":[{"id":"main","message_ids":[]}],"branch_entries":[{"role":"assistant","text":"hidden"}]}]"""
        val a=SessionCatalog.decode(raw).first{it.id=="old"};val b=SessionCatalog.decode(raw).first{it.id=="old"}
        assertEquals(a.messages,b.messages);assertEquals("selected",a.messages.single().text);assertTrue(a.historyEdited)
        assertFalse(SessionCatalog.encode(listOf(a)).contains("hidden"));assertThrows(IllegalArgumentException::class.java){HistoryEditing.scope("../../escape:history:${java.util.UUID.randomUUID()}")}
    }
    @Test fun removedSummaryAndArchivesNeverEnterRevisedContext() {
        val root=Files.createTempDirectory("linear-context").toFile()
        try {
            val s=chat();val context=ContextHistory(root,{it.readText()},{f,t->f.parentFile!!.mkdirs();f.writeText(t)})
            val future=JSONArray().put(JSONObject().put("role","system").put("content","rules")).put(JSONObject().put("role","user").put("content","FUTURE_ARCHIVE"))
            context.finish(s.id,future);HistoryEditing.replaceFrom(s,s.messages[2].id);s.messages+=Entry("user","edited")
            val key=HistoryEditing.actualContextKey(s);assertFalse(context.fresh(key,s.messages,"rules").toString().contains("FUTURE"))
            assertFalse(context.query(key,s.messages,JSONObject().put("query","FUTURE_ARCHIVE")).contains("FUTURE_ARCHIVE"))
        } finally {root.deleteRecursively()}
    }
    @Test fun automaticMemoryFiltersRemovedSources() {
        val root=Files.createTempDirectory("linear-memory").toFile()
        try {
            val store=KnowledgeStore(root,Workspace(root.resolve("workspace")),{it.readText()},{f,t->f.parentFile!!.mkdirs();f.writeText(t)})
            val memory=PersonalMemory(store);val s=Session(id="personal",kind=SessionKind.PERSONAL)
            s.messages+=Entry("user","tea",timestamp=1);s.messages+=Entry("assistant","tea reply",finalReply=true,timestamp=2);memory.recordTurn(s)
            s.messages+=Entry("user","future coffee",timestamp=3);val target=s.messages.last().id;s.messages+=Entry("assistant","future coffee reply",finalReply=true,timestamp=4);memory.recordTurn(s)
            HistoryEditing.replaceFrom(s,target);s.messages+=Entry("user","edited tea",timestamp=5)
            assertFalse(memory.recall(s,"coffee").contains("future coffee"));assertTrue(memory.recall(s,"tea").contains("tea reply"))
        } finally {root.deleteRecursively()}
    }
}
