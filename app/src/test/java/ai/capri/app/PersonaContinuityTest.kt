package ai.capri.app

import ai.capri.app.knowledge.ConversationHistory
import ai.capri.app.knowledge.KnowledgeStore
import ai.capri.app.knowledge.PersonalMemory
import ai.capri.app.knowledge.ContextHistory
import ai.capri.app.model.ContextBudget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonaContinuityTest {
    @get:Rule val temp = TemporaryFolder()
    private fun knowledge() = KnowledgeStore(temp.root, Workspace(File(temp.root, "sessions")),
        { it.readText() }, { file, text -> file.writeText(text) })
    private fun personal() = Session(id = "personal-chat", title = "Capri", kind = SessionKind.PERSONAL)
    private fun note(text: String, entry: Int = 1) = JSONObject().put("action", "remember").put("text", text).put("source_entry", entry)

    @Test fun oldSessionsKeepEveryMessageAndDefaultIdentitySurvivesReload() {
        val old = """[{"id":"old-chat","title":"Capri","messages":[{"role":"user","text":"原任务"}]}]"""
        val first = SessionCatalog.decode(old)
        assertEquals(2, first.size)
        assertEquals(SessionKind.TASK, first.first { it.id == "old-chat" }.kind)
        assertEquals(0L, first.first { it.id == "old-chat" }.messages.single().timestamp)
        val personalId = first.single { it.kind == SessionKind.PERSONAL }.id
        val reloaded = SessionCatalog.decode(SessionCatalog.encode(first))
        assertEquals(personalId, reloaded.single { it.kind == SessionKind.PERSONAL }.id)
        assertEquals("原任务", reloaded.first { it.id == "old-chat" }.messages.single().text)
        assertEquals(1, SessionCatalog.decode("[]").size)
        assertThrows(Exception::class.java) { SessionCatalog.decode("corrupt") }
    }

    @Test fun duplicatePersonalKindsDoNotDeleteHistoryAndTimestampsRoundTrip() {
        val chats = mutableListOf(personal(), personal().copy(id = "another", messages = mutableListOf(Entry("user", "保留", timestamp = 42))))
        val loaded = SessionCatalog.decode(SessionCatalog.encode(chats))
        assertEquals(1, loaded.count { it.kind == SessionKind.PERSONAL })
        assertEquals(42L, loaded.last().messages.single().timestamp)
        assertEquals("保留", loaded.last().messages.single().text)
    }

    @Test fun personalNotesSurviveReloadCorrectionAndStayOutOfGenericMemoryPanel() {
        val chat = personal()
        chat.messages.add(Entry("user", "我不喝咖啡", timestamp = 10))
        val memory = PersonalMemory(knowledge())
        val id = JSONObject(memory.execute(chat, chat, note("用户不喝咖啡"))).getString("id")
        chat.messages.add(Entry("user", "改一下，我现在喝咖啡，不过不要糖", timestamp = 20))
        memory.execute(chat, chat, note("用户现在喝咖啡，不加糖", 2).put("id", id))
        val reloaded = PersonalMemory(knowledge())
        val found = JSONArray(reloaded.execute(chat, chat, JSONObject().put("action", "search").put("query", "咖啡")))
        assertEquals(1, found.length())
        assertEquals(2, found.getJSONObject(0).getJSONArray("sources").getJSONObject(0).getInt("entry"))
        assertTrue(reloaded.recall(chat.id, "咖啡").contains("不加糖"))
        assertFalse(reloaded.recall(chat.id, "咖啡").contains("不喝咖啡"))
        assertTrue(knowledge().records(chat.id).isEmpty())
        assertEquals(0, knowledge().search(chat.id, "咖啡").length())
        assertThrows(IllegalArgumentException::class.java) { knowledge().change(chat.id, id) }
        assertThrows(IllegalArgumentException::class.java) { reloaded.execute(chat, Session(), note("导入任务")) }
        assertThrows(IllegalArgumentException::class.java) { reloaded.execute(chat, chat, note("虚构来源", 999)) }
    }

    @Test fun onlyCompletedPersonalTurnsBecomeSourcedEpisodesAndRetryIsIdempotent() {
        val memory = PersonalMemory(knowledge()); val chat = personal()
        chat.messages.add(Entry("user", "我在摸鱼", timestamp = 10))
        memory.recordTurn(chat)
        assertTrue(memory.recall(chat.id, "摸鱼").isBlank())
        chat.messages.add(Entry("assistant", "放心，我不告状。", finalReply = true, timestamp = 11))
        memory.recordTurn(chat); memory.recordTurn(chat)
        assertEquals(1, knowledge().personalRecords(chat.id).size)
        val persisted = SessionCatalog.decode(SessionCatalog.encode(listOf(chat))).single()
        assertTrue(PersonalMemory(knowledge()).recall(persisted.id, "摸鱼").contains("不告状"))
        val task = chat.copy(id = "task-chat", kind = SessionKind.TASK)
        memory.recordTurn(task)
        assertTrue(knowledge().personalRecords(task.id).isEmpty())
        chat.messages.add(Entry("user", "下一轮未回复", timestamp = 12))
        memory.recordTurn(chat)
        assertEquals(1, knowledge().personalRecords(chat.id).size)
    }

    @Test fun recallIsBoundedAndDoesNotSaveCredentialEpisodes() {
        val memory = PersonalMemory(knowledge()); val chat = personal()
        repeat(10) { i ->
            chat.messages.add(Entry("user", "调试咖啡$i" + "文".repeat(2000), timestamp = i * 2L))
            chat.messages.add(Entry("assistant", "回复".repeat(1500), finalReply = true, timestamp = i * 2L + 1))
            memory.recordTurn(chat)
        }
        assertTrue(memory.recall(chat.id, "咖啡").length <= 4200)
        val count = knowledge().personalRecords(chat.id).size
        chat.messages.add(Entry("user", "我的密码是secret-value"))
        chat.messages.add(Entry("assistant", "收到", finalReply = true))
        memory.recordTurn(chat)
        assertEquals(count, knowledge().personalRecords(chat.id).size)
    }

    @Test fun crossChatHistoryHasProvenanceUnknownLegacyTimeAndCompletePagination() {
        val chats = List(14) { i -> Session(id = "task-$i", title = "任务$i", messages = mutableListOf(Entry("user", "咖啡$i", timestamp = i.toLong()))) }
        val first = JSONObject(ConversationHistory.execute(chats, JSONObject().put("action", "list")))
        assertEquals(14, first.getInt("total")); assertEquals(12, first.getInt("next_offset"))
        val second = JSONObject(ConversationHistory.execute(chats, JSONObject().put("action", "list").put("offset", 12)))
        assertEquals(2, second.getJSONArray("results").length())
        assertTrue(second.isNull("next_offset"))
        val search = JSONObject(ConversationHistory.execute(chats, JSONObject().put("action", "search").put("query", "咖啡")))
        assertEquals("user", search.getJSONArray("results").getJSONObject(0).getString("role"))
        val body = "历史".repeat(9000)
        val long = Session(id = "long-chat", messages = mutableListOf(Entry("assistant", body, timestamp = 0)))
        val pages = (0..2).map { i -> JSONObject(ConversationHistory.execute(listOf(long), JSONObject().put("action", "read").put("chat_id", long.id).put("entry", 1).put("offset", i * 8000))) }
        assertEquals(body, pages.joinToString("") { it.getString("text") })
        assertEquals("旧消息时间未知", pages[0].getString("time"))
        assertEquals("e1", pages[0].getString("reference"))
    }

    @Test fun compactionAndModelRecoveryKeepCurrentPersonalityAndMemory() {
        val history = ContextHistory(temp.root, { it.readText() }, { f, t -> f.writeText(t) })
        val rules = "人物核心\n" + PersonaRuntime.context(SessionKind.PERSONAL, "2026-10-27T16:00+08:00", "用户不加糖")
        val entries = List(100) { Entry(if (it % 2 == 0) "user" else "assistant", "旧历史".repeat(100)) } + Entry("user", "现在怎么喝？")
        val fresh = history.fresh("personal-chat", entries, rules)
        val fitted = history.fit("personal-chat", fresh, JSONArray(), ContextBudget(18000, 2000, 1000, "test", 1.0))
        assertTrue(fitted.length() < fresh.length())
        assertEquals(rules, fitted.getJSONObject(0).getString("content"))
        val saved = RecoveryMessages.sanitized(fitted)
        val resumed = RecoveryMessages.forResume(saved)
        resumed.getJSONObject(0).put("content", rules)
        assertTrue(resumed.getJSONObject(0).getString("content").contains("用户不加糖"))
        assertFalse(PersonaRuntime.mode(SessionKind.TASK).contains("摸鱼"))
    }

    @Test fun resetOnlyDeletesRecallCachesAndNeverWorkspaceFilesOrCore() {
        val root = temp.root
        listOf("knowledge", "context-history", "agent-recovery", "web-retrieval", "sessions/chat/workspace", "runtime", "persona").forEach {
            File(root, it).mkdirs(); File(root, "$it/fixture.txt").writeText("old")
        }
        SharedMemoryReset.clearCaches(root)
        assertFalse(File(root, "knowledge").exists()); assertFalse(File(root, "context-history").exists())
        assertFalse(File(root, "agent-recovery").exists()); assertFalse(File(root, "web-retrieval").exists())
        assertTrue(File(root, "sessions/chat/workspace/fixture.txt").isFile)
        assertTrue(File(root, "runtime/fixture.txt").isFile); assertTrue(File(root, "persona/fixture.txt").isFile)
    }
}
