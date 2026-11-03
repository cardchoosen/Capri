package ai.capri.core

import ai.capri.app.agent.ToolDiscovery
import ai.capri.app.knowledge.*
import ai.capri.app.model.ContextBudget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PortableAdaptersTest {
    private fun schema(description:String)=ToolContracts.spec("skill_hub",description,JSONObject().put("action",JSONObject().put("type","string").put("enum",JSONArray().put("inspect"))),listOf("action"))
    @Test fun toolRefreshInvalidatesLoadedSchemaAndValidatesExactBridgeArguments() {
        val d=ToolDiscovery()
        d.assemble(JSONArray().put(schema("Inspect the public Skill repository.")))
        assertFalse(d.allowed("skill_hub"))
        d.execute("tool_describe",JSONObject().put("names",JSONArray().put("skill_hub")))
        assertTrue(d.allowed("skill_hub"))
        d.assemble(JSONArray().put(schema("Inspect the revised public Skill repository.")))
        assertFalse(d.allowed("skill_hub"))
        fun call(action:String)=JSONObject().put("function",JSONObject().put("name","tool_invoke").put("arguments",JSONObject().put("name","skill_hub").put("arguments",JSONObject().put("action",action)).toString()))
        assertEquals("skill_hub",d.resolve(call("inspect")).getString("name"))
        assertTrue(runCatching { d.resolve(call("publish")) }.isFailure)
        d.assemble(JSONArray())
        assertTrue(runCatching { d.resolve(call("inspect")) }.isFailure)
    }
    @Test fun contextArchivePreservesSourceAndKeepsRevisionAndHostPorts() {
        val root=Files.createTempDirectory("portable-context").toFile()
        try {
            var sanitized=0
            val revision="chat:history:11111111-1111-4111-8111-111111111111"
            val a=ContextArchive(root,{it.readText()},{f,t->f.parentFile.mkdirs();f.writeText(t)},sanitize={sanitized++;JSONArray(it.toString())},transition={_,_,_->})
            val m=a.fresh(revision,listOf(ContextEntry("user","Keep exact constraints","u"),ContextEntry("user","Skill body","s")),"rules",setOf("u"))
            assertEquals("u",m.getJSONObject(1).getString("_capri_user_entry"))
            assertEquals("skill",m.getJSONObject(2).getString("_capri_context_kind"))
            val original=m.toString()
            a.finish(revision,m)
            assertEquals(1,sanitized)
            assertEquals(original,m.toString())
            assertFalse(java.io.File(root,"chat/summary.txt").exists())
            assertTrue(a.summary(revision).contains("归档"))
            assertSame(m,a.fit(revision,m,JSONArray(),ContextBudget(100000,1000,1000,"fixture")))
            assertTrue(runCatching { HistoryScope.parse("chat:history:../../outside") }.isFailure)
        } finally { root.deleteRecursively() }
    }
    @Test fun failedArchiveWriteCannotMutateCheckpointOrPublishTransition() {
        val root=Files.createTempDirectory("portable-context-failure").toFile()
        try {
            var transitions=0
            val a=ContextArchive(root,{it.readText()},{_,_->error("disk full")},sanitize={JSONArray(it.toString())},transition={_,_,_->transitions++})
            val m=JSONArray().put(JSONObject().put("role","system").put("content","rules"))
                .put(JSONObject().put("role","user").put("content","first"))
                .put(JSONObject().put("role","assistant").put("content","x".repeat(20000)))
                .put(JSONObject().put("role","user").put("content","latest"))
            val original=m.toString()
            assertTrue(runCatching { a.fit("chat",m,JSONArray(),ContextBudget(6000,500,500,"fixture")) }.isFailure)
            assertEquals(original,m.toString());assertEquals(0,transitions)
        } finally { root.deleteRecursively() }
    }
}
