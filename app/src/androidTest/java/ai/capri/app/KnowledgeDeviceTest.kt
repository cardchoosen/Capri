package ai.capri.app

import ai.capri.app.knowledge.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KnowledgeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun managementEntryAndTabs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard", "am start -W -n ai.capri.app/.MainActivity")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null || node.packageName?.toString() != "ai.capri.app") return null
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
            return null
        }
        fun await(text: String): android.view.accessibility.AccessibilityNodeInfo {
            repeat(60) {
                instrumentation.uiAutomation.clearCache()
                find(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }
                Thread.sleep(100)
            }
            error("Missing UI control: $text")
        }
        fun click(text: String) {
            var node: android.view.accessibility.AccessibilityNodeInfo? = await(text)
            while (node != null && !node.isClickable) node = node.parent
            assertNotNull(node); assertTrue(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        click("运行"); click("上下文 / 记忆 / 文档")
        await("历史摘录摘要")
        click("长期记忆"); await("保存记忆")
        click("文档检索"); await("建立 / 更新索引")
        click("上下文"); await("检索历史"); click("关闭")
    }
    @Test fun persistenceSourcesScopesDisableDeleteAndSnapshots() {
        val root = File(context.cacheDir, "knowledge-test-${UUID.randomUUID()}").apply { mkdirs() }
        val workspace = Workspace(File(root, "sessions")); val dir = File(root, "index")
        try {
            var store = KnowledgeStore(dir, workspace)
            val local = store.putMemory("chat-a", "测试偏好：蓝色风铃", "chat:chat-a/e1")
            val shared = store.putMemory("chat-a", "明确共享：紫色风铃", "用户明确共享", true)
            assertEquals(2, store.search("chat-a", "风铃").length())
            assertEquals(1, store.search("chat-b", "风铃").length())
            assertTrue(store.records("chat-b").none { it.getString("id") == local.getString("id") })
            assertTrue(runCatching { store.change("chat-b", local.getString("id")) }.isFailure)
            store = KnowledgeStore(dir, workspace) // Reload from atomic files, no in-memory index.
            assertEquals(2, store.search("chat-a", "风铃").length())
            val retrieval = JSONArray().put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "knowledge-search").put("function", JSONObject().put("name", "knowledge").put("arguments", "{\"action\":\"search\",\"query\":\"风铃\"}")))))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "knowledge-search").put("content", KnowledgeTool(store).execute("chat-a", emptyList(), JSONObject().put("action", "search").put("query", "风铃"))))
            store.change("chat-a", local.getString("id"), false)
            assertEquals(1, store.search("chat-a", "风铃").length())
            KnowledgeTool(store).refreshResults("chat-a", retrieval)
            assertFalse(retrieval.getJSONObject(1).getString("content").contains("蓝色风铃"))
            assertTrue(retrieval.getJSONObject(1).getString("content").contains("紫色风铃"))
            store.change("chat-b", shared.getString("id"))
            assertEquals(0, store.search("chat-a", "风铃").length())
            store.putMemory("chat-a", "新偏好：绿色风铃", "用户编辑", false, local.getString("id"))
            assertEquals(0, store.search("chat-a", "风铃").length()) // Editing preserves disabled state.
            store.change("chat-a", local.getString("id"), true)
            assertEquals(1, store.search("chat-a", "绿色风铃").length())
            workspace.file("chat-a", "guide.md").writeText("# 测试资料\n雪豹项目的验收码是ALPHA-928。\n第三行")
            val doc = store.index("chat-a", "guide.md")
            val hit = store.search("chat-a", "ALPHA-928").getJSONObject(0)
            assertEquals(doc.getString("id") + ":1", hit.getString("citation"))
            assertEquals(1, hit.getInt("line_start")); assertEquals(3, hit.getInt("line_end"))
            assertEquals(64, hit.getString("sha256").length)
            assertEquals(0, store.search("chat-b", "ALPHA-928").length())
            workspace.file("chat-a", "guide.md").writeText("雪豹项目的新验收码BETA-317。")
            assertEquals(1, store.search("chat-a", "ALPHA-928").length()) // Explicit snapshot.
            store.index("chat-a", "guide.md")
            assertEquals(0, store.search("chat-a", "ALPHA-928").length())
            assertEquals(1, store.search("chat-a", "BETA-317").length())
            store.change("chat-a", doc.getString("id"))
            assertEquals(0, KnowledgeStore(dir, workspace).search("chat-a", "BETA-317").length())
            assertTrue(workspace.file("chat-a", "guide.md").isFile)
            workspace.file("chat-a", "bad.pdf").writeText("Not a PDF")
            assertTrue(runCatching { store.index("chat-a", "bad.pdf") }.isFailure)
            assertTrue(runCatching { store.index("chat-a", "../index/knowledge.json") }.isFailure)
            workspace.file("chat-a", "bad.txt").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            assertTrue(runCatching { store.index("chat-a", "bad.txt") }.isFailure)
            assertTrue(runCatching { store.putMemory("chat-a", "sk-" + "a".repeat(32), "user") }.isFailure)
            val tool = KnowledgeTool(store)
            assertTrue(runCatching { tool.execute("chat-a", listOf(Entry("assistant", "invented")), JSONObject().put("action", "remember").put("source_entry", 1).put("text", "事实")) }.isFailure)
            val remembered = JSONObject(tool.execute("chat-a", listOf(Entry("user", "请记住仅为本会话测试：雪松纸飞机")), JSONObject().put("action", "remember").put("text", "雪松纸飞机")))
            assertTrue(remembered.getString("source").endsWith("e1 · 用户消息"))
            val failure = JSONArray().put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "failed").put("function", JSONObject().put("name", "knowledge").put("arguments", "{\"action\":\"remember\"}")))))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "failed").put("content", "工具失败：缺少text"))
            tool.refreshResults("chat-a", failure)
            assertEquals("工具失败：缺少text", failure.getJSONObject(1).getString("content"))
            assertEquals("guide.md", workspace.file("chat-a", "guide.md").name)
        } finally { root.deleteRecursively() }
    }
    @Test fun longHistoryBudgetArchivingAndRecoveryPairing() {
        val root = File(context.cacheDir, "context-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val engine = ContextHistory(root)
            val entries = (1..80).map { Entry(if (it % 2 == 1) "user" else "assistant", if (it == 1) "初始目标：建造雪豹工作台" else "第${it}条决定：保留文件artifact-$it.txt") }
            val fresh = engine.fresh("chat-a", entries, "CORE-PERSONA")
            assertTrue(fresh.toString().contains("初始目标"))
            assertEquals(81, fresh.length())
            assertTrue(engine.query("chat-a", entries, JSONObject().put("query", "artifact-20")).contains("e20"))
            assertTrue(ContextHistory(root).query("chat-a", entries, JSONObject().put("reference", "e1")).contains("雪豹工作台"))
            assertTrue(fresh.toString().contains("第80条决定"))
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "CORE-PERSONA"))
                .put(JSONObject().put("role", "user").put("content", "当前目标：保留我的全部源文件"))
            repeat(8) { i ->
                messages.put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "id-$i").put("type", "function").put("function", JSONObject().put("name", "write_file").put("arguments", "{\"path\":\"artifact-$i.txt\"}")))))
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", "id-$i").put("content", "结果未确认；禁止重跑。" + "x".repeat(18000)))
            }
            messages.put(JSONObject().put("role", "assistant").put("content", "最新状态"))
            val budget = ai.capri.app.model.ModelCapacity(32768, 4096, "test").budget()
            val fitted = engine.fit("chat-a", messages, JSONArray(), budget)
            assertTrue(budget.fits(ai.capri.app.model.ContextTokens.estimate(fitted, JSONArray())))
            assertEquals("CORE-PERSONA", fitted.getJSONObject(0).getString("content"))
            assertTrue(fitted.toString().contains("当前目标：保留我的全部源文件"))
            val calls = mutableSetOf<String>(); val results = mutableSetOf<String>()
            for (i in 0 until fitted.length()) { val m = fitted.getJSONObject(i); m.optJSONArray("tool_calls")?.let { calls += it.getJSONObject(0).getString("id") }; if (m.optString("role") == "tool") results += m.getString("tool_call_id") }
            assertEquals(calls, results)
            val archive = File(root, "chat-a").listFiles()!!.first { it.name.startsWith("c") }
            assertTrue(ContextHistory(root).query("chat-a", entries, JSONObject().put("reference", archive.nameWithoutExtension)).contains("write_file"))
            val huge = JSONArray().put(JSONObject().put("role", "system").put("content", "CORE"))
                .put(JSONObject().put("role", "user").put("content", "x".repeat(100000)))
            assertTrue(runCatching { engine.fit("chat-a", huge, JSONArray(), budget) }.isFailure)
            assertEquals("CORE", huge.getJSONObject(0).getString("content"))
            engine.finish("chat-a", fitted)
            assertTrue(ContextHistory(root).fresh("chat-a", entries, "CORE-PERSONA").toString().contains("最近完成任务归档"))
            val sensitive = JSONArray().put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "secret-knowledge").put("function", JSONObject().put("name", "knowledge").put("arguments", "{\"text\":\"DELETED-MEMORY\"}")))))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "secret-knowledge").put("content", "DELETED-MEMORY"))
            assertFalse(RecoveryMessages.sanitized(sensitive).toString().contains("DELETED-MEMORY"))
            val recovery = AgentRecovery(File(root, "recovery")); recovery.save("chat-a", fitted)
            assertTrue(recovery.load("chat-a")!!.length() > 0)
        } finally { root.deleteRecursively() }
    }
}
