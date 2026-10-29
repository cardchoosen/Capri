package ai.capri.app

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ai.capri.app.extensions.ToolsCatalog
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolLanguageDeviceTest {
    @Test fun actualModelContractsAndToolsDirectoryUseEnglishWithoutRenamingIdentifiers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        var builtins: JSONArray? = null
        for (attempt in 0 until 50) {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull { !it.isDestroyed && !it.isFinishing }?.let {
                    builtins = ViewModelProvider(it)[CapriModel::class.java].builtinTools()
                }
            }
            if (builtins != null) break
            Thread.sleep(100)
        }
        val definitions = requireNotNull(builtins)
        assertEquals(22, definitions.length())
        assertFalse(definitions.toString().any { it in '\u4e00'..'\u9fff' })
        val catalog = ToolsCatalog.entries(definitions, emptyList(), { toolLabel(it, null) }, { it })
        assertEquals(22, catalog.map { it.name }.distinct().size)
        for (entry in catalog) assertFalse(entry.name, (entry.title + entry.description + entry.parameters).any { it in '\u4e00'..'\u9fff' })
        assertEquals("View model", catalog.single { it.name == "model_info" }.title)
        assertEquals("Read current chat history", catalog.single { it.name == "context_history" }.title)
        assertEquals("Read all chat histories", catalog.single { it.name == "conversation_history" }.title)
        assertTrue(catalog.any { it.name == "profile_search" })
    }
}
