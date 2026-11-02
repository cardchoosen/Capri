package ai.capri.app.agent

import ai.capri.app.*
import ai.capri.app.files.FileToolContract
import ai.capri.app.knowledge.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolSurfaceTest {
    @Test fun completeBuiltinCatalogStaysDiscoverableWithFewerEagerSchemaTokens() {
        val all = JSONArray((FileToolContract.specifications() + ai.capri.app.coding.CodingContract.specifications() + listOf(
            FileToolContract.sendSpecification(), ai.capri.app.model.ModelInfoTool.specification(), KnowledgeTool.specification(),
            KnowledgeTool.historySpecification(), PersonalMemory.specification(), TaskExperienceContract.specification(), ConversationHistory.specification(),
            ShortTaskContract.specification(), ai.capri.app.program.ProgramContract.specification(), ai.capri.app.coding.CodingAssistContract.specification(), CapabilityContract.specification(), ai.capri.app.nativebridge.NativeCatalog.spec(),
            ai.capri.app.terminal.TerminalContract.specification(), ai.capri.app.browser.BrowserContract.specification(),
            ai.capri.app.extensions.ExtensionInstall.specification(),
            ai.capri.app.retrieval.WebContract.search(), ai.capri.app.retrieval.WebContract.read(), TaskState.specification())))
        val discovery = ToolDiscovery()
        val eager = discovery.assemble(all)
        val oldCost = ai.capri.app.model.ContextTokens.text(all.toString())
        val newCost = ai.capri.app.model.ContextTokens.text(eager.toString()) + ai.capri.app.model.ContextTokens.message(discovery.manifest())
        println("HARNESS_SURFACE " + JSONObject().put("all_tools", all.length()).put("eager_tools", eager.length())
            .put("old_schema_estimate", oldCost).put("new_schema_and_manifest_estimate", newCost).toString())
        val controlCost = ai.capri.app.model.ContextTokens.text(JSONArray(discovery.specifications()).toString())
        // Eager Android/browser deliberately trade schema cost for avoiding discovery turns.
        assertTrue("Full catalog cost=$oldCost, new surface cost=$newCost", newCost < oldCost + controlCost + 2500)
        assertTrue(discovery.allowed("android"));assertTrue(discovery.allowed("browser"))
        val names = (0 until all.length()).map { all.getJSONObject(it).getJSONObject("function").getString("name") }
        for (name in names) {
            if (!discovery.allowed(name)) assertTrue(name, discovery.manifest().toString().contains(name))
        }
        for (spec in listOf(TaskState.specification()) + discovery.specifications()) assertFalse(spec.toString().any { it in '\u4e00'..'\u9fff' })
    }
}
