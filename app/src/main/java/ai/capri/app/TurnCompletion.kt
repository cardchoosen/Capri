package ai.capri.app

import org.json.JSONArray
import org.json.JSONObject

/** A bounded guard for obvious action preambles, not a general proof of task completion. */
internal class TurnCompletion {
    enum class Decision { ACCEPT, CONTINUE, INCOMPLETE }
    var corrections = 0
        private set

    fun decide(request: String, reply: String): Decision {
        if (!suspicious(request, reply)) return Decision.ACCEPT
        if (corrections >= 2) return Decision.INCOMPLETE
        corrections++
        return Decision.CONTINUE
    }

    companion object {
        const val guidance = "Task execution: An action preamble is not a completed task. For an authorized action request, use the available tools and continue until the requested work is done or a concrete blocker, permission boundary or required user answer is reached. Do not stop after promising to inspect, edit or run something. A tool returning is not proof of task success. Report verified results and unresolved work accurately. Answer questions and planning requests normally; never infer authorization from an earlier assistant promise. Personality controls wording, not whether execution is complete."
        const val correction = "The previous response appears to be an action preamble without the promised tool call. Reassess the latest user request within its authorized scope. If action is authorized and still needed, execute the next appropriate tool now and continue the task. If this is only a question or plan, answer it directly. If blocked, awaiting confirmation or unable to proceed, explain that explicitly. Do not repeat the preamble, invent results, expand permissions, or replay completed actions."

        private val planning = Regex("(?:先别|先不|暂不|不要|不用|只).{0,12}(?:执行|实施|修改|动手)|(?:先|只).{0,8}(?:给.{0,4}方案|说.{0,4}方案|解释|讨论|分析|规划)|(?:方案|原理|怎么做|如何做|为什么|什么意思)[？?]?$|(?:do not|don't|only|just) (?:execute|run|edit|explain|plan)|(?:give|show) me (?:a |the )?plan", RegexOption.IGNORE_CASE)
        private val blocked = Regex("(?:需要|等待|请你|请先|你先|是否|能否|要不要|确认后|确认前|同意后|授权后|不能|无法|失败|未授权|没有权限|缺少)|(?:need your|await|please confirm|permission|cannot|can't|unable|blocked|failed|would you|shall I|should I)", RegexOption.IGNORE_CASE)
        private val preamble = Regex("^(?:(?:好[的啊]?|行|明白|收到|别催|接下来|下一步|现在)[，,。.!！：:\\s—-]*)?(?:(?:我|我们)(?:先|会|要|将|来|接下来|现在|马上|这就|再|继续)|先|继续|接下来|下一步|查)(?:.{0,90})(?:看|查|读|检查|确认|定位|修改|改|写|执行|运行|拉|fetch|git|文档|文件|状态)|^(?:I(?:'ll| will| am going to)|Let me|Next,? I(?:'ll| will))\\s+(?:first\\s+)?(?:check|inspect|read|look|edit|update|run|fetch|review|write)\\b", RegexOption.IGNORE_CASE)

        /** Conservative explicit-action recognition; ambiguous requests remain model judgments. */
        fun requiresEvidence(request: String): Boolean {
            if (planning.containsMatchIn(request) || Regex("(?:代码|用法|命令).{0,5}示例|example|pseudocode|haiku|poem|story|essay|写.{0,8}(?:诗|故事|文案|草稿)|(?:write|create) (?:a |the )?(?:plan|proposal|outline)", RegexOption.IGNORE_CASE).containsMatchIn(request)) return false
            val chinese = Regex("""(?:^|[。！!\n])\s*(?:(?:请|帮我|麻烦|现在|继续|替我|把|将).{0,24})?(?:执行|运行|安装|卸载|提交|推送|(?:修改|修复|创建|写入|删除|更新|同步)(?=.{0,60}(?:文件|代码|项目|仓库|目录|配置|文档|API|应用|依赖|测试|[a-zA-Z0-9_-]+\.[a-zA-Z0-9]{1,12})))""", RegexOption.IGNORE_CASE)
            val english = Regex("""^(?:(?:please|can you|could you)\s+)?(?:run|execute|install|uninstall|commit|push|(?:edit|fix|create|write|delete|update)(?=.{0,60}\b(?:file|directory|folder|repository|project|bug|build|test|dependency|package)\b))\b""", RegexOption.IGNORE_CASE)
            return chinese.containsMatchIn(request) || english.containsMatchIn(request)
        }

        fun suspicious(request: String, reply: String): Boolean {
            if (planning.containsMatchIn(request.trim()) || reply.isBlank() || reply.contains("```")) return false
            // Focus on the closing promise; completed work earlier in the reply must not hide it.
            val tail = reply.trim().substringAfterLast("\n\n").trim().trimStart('*', ' ', '-', '\n')
            if (tail.length > 400 || tail.contains('?') || tail.contains('？') || blocked.containsMatchIn(tail)) return false
            if (tail.startsWith("你") || tail.startsWith("建议") || tail.startsWith("可以先") || tail.startsWith("例如")) return false
            val lastSentence = tail.trimEnd('。', '！', '!', '.').split(Regex("[。！!]")).last().trim()
            return listOf(tail, lastSentence).any { candidate ->
                preamble.containsMatchIn(candidate) && !Regex("(?:看|查|读|写|检查|查看|修改|更新|同步|执行|推送|运行)(?:了|过|完)|(?:已经|已完成|已更新|已修改)|\\b(?:checked|updated|completed|inspected)\\b", RegexOption.IGNORE_CASE).containsMatchIn(candidate)
            }
        }

        /** Request-only instruction, after style reminders; no synthetic user turn or growing history. */
        fun requestMessages(messages: JSONArray): JSONArray = JSONArray((0 until messages.length()).map { messages.getJSONObject(it) })
            .put(JSONObject().put("role", "system").put("content", guidance))
    }
}
