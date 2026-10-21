package ai.capri.app

import org.junit.Assert.*
import org.junit.Test

class ConversationPolicyTest {
    @Test fun identityRecognizesStandaloneQuestions() {
        listOf("你是谁", " 你是谁？ ", "请问你是谁？", "介绍一下你自己", "Who are you?", "WHAT'S YOUR NAME!").forEach {
            assertTrue(it, IdentityReply.matches(it))
        }
    }
    @Test fun tasksQuotesAndCompoundRequestsStillReachAgent() {
        listOf("写一个回答你是谁的程序", "翻译：你是谁", "“你是谁”", "你是谁，顺便分析这个文件", "你为什么叫摩羯", "你是谁？再介绍你的经历").forEach {
            assertFalse(it, IdentityReply.matches(it))
        }
    }
    @Test fun internalLookupsAreHiddenInLegacyHistoryAndProgress() {
        assertFalse(ToolPresentation.visible(Entry("tool", "profile_search\nsecret archive")))
        assertEquals("思考中", ToolPresentation.progress("profile_search"))
        assertFalse(ToolPresentation.showPreamble(listOf("profile_search", "read_file")))
        assertTrue(ToolPresentation.visible(Entry("assistant", "profile_search 是内部检索工具")))
        assertTrue(ToolPresentation.visible(Entry("tool", "python_run\nexit=0")))
        assertTrue(ToolPresentation.showPreamble(listOf("python_run")))
    }
}
