package ai.capri.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TurnCompletionTest {
    @Test fun actualPrematureRepliesAreRecognizedAndBounded() {
        val samples = listOf(
            "明白，两个变更我接住了。\n\n先看当前 git 状态和 weapon-forge 的 SKILL.md，确认要改什么、以及怎么合到 main。",
            "先看 CapriDocs 的 git 状态和几个文档的现状，再决定怎么改、改哪几处。",
            "别催，我先看现场再动笔——不然又改错地方。查 CapriDocs 的 git 状态和几个文档内容。",
            "好，PR 不开。先 fetch origin 看有没有新提交，再决定 rebase 还是 merge。先拉最新。",
            "I'll first inspect the repository and update the file.")
        for (reply in samples) {
            val guard = TurnCompletion()
            assertEquals(reply, TurnCompletion.Decision.CONTINUE, guard.decide("同步一下", reply))
            assertEquals(TurnCompletion.Decision.CONTINUE, guard.decide("同步一下", reply))
            assertEquals(TurnCompletion.Decision.INCOMPLETE, guard.decide("同步一下", reply))
            assertEquals(2, guard.corrections)
        }
    }

    @Test fun answersPlansCompletedWorkAndPermissionBoundariesAreNotForcedIntoExecution() {
        val examples = listOf(
            "你是谁" to "我是 Capri。",
            "先不要执行，给我方案" to "我先检查 git 状态，再决定改哪些文件。",
            "你先分析下，给个方案，我来决策" to "先看当前状态，再修改文件。",
            "Just give me a plan" to "I'll inspect the repository first.",
            "同步一下" to "文档已更新，检查通过。",
            "同步一下" to "我修改了三个文件，测试通过。",
            "同步一下" to "我先查看了文件，已经完成修改。",
            "同步一下" to "先检查状态，需要你的确认才能提交。",
            "同步一下" to "我先查看仓库，但当前无法连接手机。",
            "同步一下" to "我先修改，是否允许提交？",
            "给点建议" to "你可以先检查状态，再决定如何修改。",
            "解释一下" to "建议先检查依赖。",
            "怎么做" to "先查看文件，再修改。",
            "演示代码" to "```text\n我先检查文件\n```"
        )
        for ((request, reply) in examples) assertEquals(reply, TurnCompletion.Decision.ACCEPT, TurnCompletion().decide(request, reply))
    }

    @Test fun correctionCountBelongsToTurnAndRequestRulesDoNotGrowHistoryOrDisturbToolPairs() {
        val guard = TurnCompletion(); guard.decide("改一下", "我先检查文件。")
        assertEquals(TurnCompletion.Decision.ACCEPT, guard.decide("改一下", "文件已修改并检查。"))
        val history = JSONArray("""[{"role":"system","content":"rules"},{"role":"assistant","tool_calls":[{"id":"one"}]},{"role":"tool","tool_call_id":"one","content":"evidence"}]""")
        val before = history.toString()
        val request = TurnCompletion.requestMessages(history)
        assertEquals(before, history.toString())
        assertEquals("one", request.getJSONObject(2).getString("tool_call_id"))
        assertEquals(TurnCompletion.guidance, request.getJSONObject(request.length()-1).getString("content"))
        assertEquals(request.length(), TurnCompletion.requestMessages(history).length())
    }

    @Test fun diagnosticsRecordOnlyAllowlistedReasonsCountsAndDecisions() {
        val dir = java.nio.file.Files.createTempDirectory("completion-metrics").toFile()
        try {
            val file = dir.resolve("trace.jsonl"); val trace = PerformanceTrace(file)
            trace.modelDecision(JSONObject().put("finish_reason", "secret-value").put("native_finish_reason", "end_turn"), 0, "continue_preamble", 1)
            trace.finish(PerformanceTrace.Status.INCOMPLETE)
            val lines=file.readLines().map(::JSONObject)
            assertFalse(file.readText().contains("secret-value"))
            val decision=lines.single{it.optString("stage")=="model_decision"}
            assertEquals("unknown",decision.getString("finish_reason"));assertEquals("end_turn",decision.getString("native_finish_reason"))
            assertEquals("incomplete",lines.last().getString("status"))
        } finally { dir.deleteRecursively() }
    }
}
