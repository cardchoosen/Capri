package ai.capri.app

import ai.capri.app.browser.BrowserContract
import ai.capri.app.extensions.ExtensionInstall
import ai.capri.app.files.FileToolContract
import ai.capri.app.knowledge.ConversationHistory
import ai.capri.app.knowledge.KnowledgeTool
import ai.capri.app.knowledge.PersonalMemory
import ai.capri.app.model.ModelInfoTool
import ai.capri.app.nativebridge.NativeCatalog
import ai.capri.app.retrieval.WebContract
import ai.capri.app.terminal.TerminalContract
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolLanguageTest {
    @Test fun builtInContractsAndActionLabelsAreEnglish() {
        val definitions = FileToolContract.specifications() + listOf(
            FileToolContract.sendSpecification(), ModelInfoTool.specification(), KnowledgeTool.specification(),
            KnowledgeTool.historySpecification(), PersonalMemory.specification(), ConversationHistory.specification(),
            ShortTaskContract.specification(), CapabilityContract.specification(), NativeCatalog.spec(), TerminalContract.specification(),
            BrowserContract.specification(), ExtensionInstall.specification(), WebContract.search(), WebContract.read())
        assertEquals(19, definitions.size) // profile_search lives in CapriModel and is checked on device.
        for (definition in definitions) {
            val function = definition.getJSONObject("function"); val name = function.getString("name")
            assertTrue(name, name.matches(Regex("[a-z][a-z0-9_]*")))
            assertFalse(name, function.toString().any { it in '\u4e00'..'\u9fff' })
            assertFalse(name, toolLabel(name, null).any { it in '\u4e00'..'\u9fff' })
            function.getJSONObject("parameters").optJSONObject("properties")?.optJSONObject("action")?.optJSONArray("enum")?.let { actions ->
                for (i in 0 until actions.length()) assertFalse(name, toolLabel(name, JSONObject().put("action", actions.getString(i))).any { it in '\u4e00'..'\u9fff' })
            }
        }
        for (action in NativeCatalog.actions) {
            assertFalse(action.name, (action.title + action.parameters).any { it in '\u4e00'..'\u9fff' })
            assertEquals(action.title, toolLabel("android", JSONObject().put("action", action.name)))
        }
        assertEquals("View model", toolLabel("model_info", null))
        assertNotEquals(toolLabel("context_history", null), toolLabel("conversation_history", null))
    }
}
