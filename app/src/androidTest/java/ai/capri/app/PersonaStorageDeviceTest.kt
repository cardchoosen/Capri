package ai.capri.app

import android.content.Context
import android.content.ContextWrapper
import ai.capri.app.knowledge.KnowledgeStore
import ai.capri.app.knowledge.PersonalMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** All writes and the destructive reset use an isolated fixture directory, never real chat data. */
@RunWith(AndroidJUnit4::class)
class PersonaStorageDeviceTest {
    private fun fixture(block: (Context, File) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val token = "persona-fixture-${UUID.randomUUID()}"
        val root = File(target.cacheDir, token).apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences("$token-$name", mode)
        }
        try { block(context, root) }
        finally { root.deleteRecursively(); target.deleteSharedPreferences("$token-config") }
    }

    @Test fun upgradeAddsOnePersonalChatAndRestoresLastSelectedTask() = fixture { context, root ->
        File(root, "sessions.json").writeText("""[{"id":"old-task","title":"之前的任务","messages":[{"role":"user","text":"原消息"}]}]""")
        val store = Store(context); val sessions = store.load()
        val personal = sessions.single { it.kind == SessionKind.PERSONAL }
        assertEquals(personal.id, store.selected(sessions))
        store.save(sessions); store.select("old-task")
        val reloaded = Store(context)
        val chats = reloaded.load()
        assertEquals("old-task", reloaded.selected(chats))
        assertEquals(1, chats.count { it.kind == SessionKind.PERSONAL })
        assertEquals("原消息", chats.first { it.id == "old-task" }.messages.single().text)
        assertEquals(0L, chats.first { it.id == "old-task" }.messages.single().timestamp)
    }

    @Test fun durableResetResumesAfterSimulatedProcessDeathWithoutTouchingArtifacts() = fixture { context, root ->
        var store = Store(context)
        val chats = store.load()
        val personal = chats.single { it.kind == SessionKind.PERSONAL }
        personal.messages.add(Entry("user", "我喜欢不加糖", timestamp = 10))
        personal.messages.add(Entry("assistant", "记住啦", finalReply = true, timestamp = 11))
        val task = Session(title = "不应残留的任务标题", messages = mutableListOf(Entry("user", "任务原文")))
        chats.add(task); store.save(chats)
        val knowledge = KnowledgeStore(File(root, "knowledge"), store.workspace)
        val memory = PersonalMemory(knowledge)
        memory.recordTurn(personal)
        memory.execute(personal, personal, JSONObject().put("action", "remember").put("text", "用户不加糖").put("source_entry", 1))
        knowledge.putMemory(task.id, "旧全局记忆", "test user", true)
        assertTrue(memory.recall(personal.id, "糖").contains("不加糖"))
        val artifact = store.workspace.file(task.id, "keep.txt").apply { writeText("任务文件保留") }
        listOf("context-history", "agent-recovery", "web-retrieval").forEach { File(root, it).mkdirs(); File(root, "$it/old.json").writeText("旧缓存") }
        SharedMemoryReset.begin(context)
        assertTrue(SharedMemoryReset.pending(context))
        // Reconstruct Store as on a fresh process; the intent is completed before a model can be used.
        store = Store(context); SharedMemoryReset.resume(context, store)
        assertFalse(SharedMemoryReset.pending(context))
        val reloaded = store.load()
        assertEquals(chats.map { it.id }, reloaded.map { it.id })
        assertTrue(reloaded.all { it.messages.isEmpty() })
        assertFalse(reloaded.any { it.title.contains("不应残留") })
        assertTrue(PersonalMemory(KnowledgeStore(File(root, "knowledge"), store.workspace)).recall(personal.id, "糖").isBlank())
        assertEquals(0, KnowledgeStore(File(root, "knowledge"), store.workspace).search(task.id, "旧全局记忆").length())
        assertEquals("任务文件保留", artifact.readText())
        assertFalse(File(root, "context-history").exists()); assertFalse(File(root, "agent-recovery").exists())
        SharedMemoryReset.resume(context, store) // No repeated reset without an intent.
        assertEquals(1, store.load().count { it.kind == SessionKind.PERSONAL })
        val core = context.assets.open("persona/core.txt").bufferedReader().use { it.readText() }
        assertTrue(core.contains("19岁")); assertTrue(core.contains("不老成"))
    }
}
