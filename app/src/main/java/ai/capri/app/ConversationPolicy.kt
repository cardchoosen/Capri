package ai.capri.app

import java.text.Normalizer
import java.util.Locale

/** Fixed identity wording belongs to the application, not model-generated prose. */
object IdentityReply {
    const val TEXT = "我是 Capri，摩羯——你在数字世界的执行者。"
    private val questions = setOf(
        "你是谁", "你是谁啊", "你是谁呀", "你是谁呢", "请问你是谁", "你好你是谁",
        "你叫什么", "你叫什么名字", "你的名字是什么", "你是哪个", "你是什么身份",
        "介绍一下你自己", "请介绍一下你自己", "介绍下你自己", "自我介绍一下", "自我介绍",
        "who are you", "what is your name", "what's your name", "introduce yourself"
    )
    fun matches(text: String): Boolean {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
            .trimEnd('?', '!', '.', '。', ' ', '\n', '\r').lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
        return normalized in questions
    }
}

/** Internal lookups never become conversation events or execution cards. */
object ToolPresentation {
    fun isInternal(name: String) = name in setOf("profile_search", "model_info", "personal_memory", "conversation_history")
    fun progress(name: String) = if (isInternal(name)) "思考中" else "执行 · $name"
    fun visible(entry: Entry) = entry.role != "tool" || !isInternal(entry.text.substringBefore('\n').trim())
    fun showPreamble(toolNames: List<String>) = toolNames.none(::isInternal)
}
