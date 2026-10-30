package ai.capri.app

import ai.capri.app.agent.ToolDiscovery
import ai.capri.app.agent.CompletionEvidence
import ai.capri.app.knowledge.KnowledgeStore
import ai.capri.app.knowledge.KnowledgeTool
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real knowledge dispatch and atomic recovery, isolated fixture storage; no model/MCP network calls. */
@RunWith(AndroidJUnit4::class)
class CapabilityDiscoveryDeviceTest {
    @Test fun bridgeUsesRealScopedKnowledgeAndPreservesRecoveryPrivacy() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"capability-discovery-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val workspace=Workspace(File(root,"workspace"))
            val store=KnowledgeStore(File(root,"knowledge"),workspace);val tool=KnowledgeTool(store)
            val discovery=ToolDiscovery();val all=JSONArray().put(KnowledgeTool.specification())
            discovery.assemble(all)
            assertFalse(discovery.allowed("knowledge"))
            val call=JSONObject().put("id","fixture-write").put("type","function").put("function",JSONObject().put("name","tool_invoke").put("arguments",
                JSONObject().put("name","knowledge").put("arguments",JSONObject().put("action","remember").put("text","FIXTURE PRIVATE FACT")).toString()))
            val history=JSONArray().put(JSONObject().put("role","system").put("content","rules"))
                .put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(call)))
            val recovery=AgentRecovery(File(root,"recovery"));recovery.save("fixture",history)
            val before=history.toString();val begin=android.os.SystemClock.elapsedRealtime()
            val function=discovery.refreshAndResolve(call,all,JSONArray())
            val result=tool.execute("fixture",listOf(Entry("user","Remember this fixture fact")),JSONObject(function.getString("arguments")))
            val elapsed=android.os.SystemClock.elapsedRealtime()-begin
            assertEquals(before,history.toString())
            assertEquals(1,store.records("fixture").size);assertTrue(store.records("other").isEmpty())
            val id=JSONObject(result).getString("id")
            history.put(JSONObject().put("role","tool").put("tool_call_id","fixture-write").put("content",result))
            recovery.save("fixture",history)
            val restored=requireNotNull(AgentRecovery(File(root,"recovery")).load("fixture"))
            assertFalse(restored.toString().contains("FIXTURE PRIVATE FACT"))
            assertEquals(1,store.records("fixture").size) // Loading a checkpoint never executes the operation.
            val searchCall=JSONObject().put("id","fixture-read").put("function",JSONObject().put("name","tool_invoke").put("arguments",
                JSONObject().put("name","knowledge").put("arguments",JSONObject().put("action","search").put("query","FIXTURE PRIVATE FACT")).toString()))
            val read=discovery.resolve(searchCall)
            history.put(JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(searchCall)))
            history.put(JSONObject().put("role","tool").put("tool_call_id","fixture-read").put("content",tool.execute("fixture",emptyList(),JSONObject(read.getString("arguments")))))
            assertTrue(history.getJSONObject(history.length()-1).getString("content").contains("FIXTURE PRIVATE FACT"))
            store.change("fixture",id,null);tool.refreshResults("fixture",history)
            assertFalse(history.getJSONObject(history.length()-1).getString("content").contains("FIXTURE PRIVATE FACT"))
            try { discovery.refreshAndResolve(call,JSONArray(),JSONArray());fail() } catch(_:IllegalStateException) {}
            assertTrue(CompletionEvidence.unsuccessful("{\"isError\":true}"))
            android.util.Log.i("CapabilityFixture",JSONObject().put("local_dispatch_ms",elapsed).put("bridge_invocations",discovery.report().getInt("bridge_invocations")).put("provider_calls",0).toString())
        } finally { root.deleteRecursively() }
    }
}
