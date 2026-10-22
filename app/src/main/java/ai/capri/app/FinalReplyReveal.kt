package ai.capri.app

/** Display-only clock; complete content is persisted immediately, this marker is never serialized. */
class FinalReplyReveal internal constructor(private val startedNanos: Long = System.nanoTime()) {
    @Volatile private var complete = false
    internal fun finish() { complete = true }
    internal fun visibleUnits(total: Int, nowNanos: Long = System.nanoTime()): Int {
        if (complete || total == 0) return total
        val durationMillis = (total * 18L).coerceIn(180, 8000)
        val elapsed = ((nowNanos - startedNanos) / 1_000_000).coerceAtLeast(0)
        return if (elapsed >= durationMillis) total else (total.toLong() * elapsed / durationMillis).toInt()
    }
}

/** Unicode-safe reveal boundaries, including combining marks, emoji modifiers, ZWJ chains and flags. */
internal object RevealText {
    private val marks = setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
    fun boundaries(text: CharSequence): IntArray {
        val ends = ArrayList<Int>()
        var i = 0
        fun consume() { i += Character.charCount(Character.codePointAt(text, i)) }
        fun regional(cp: Int) = cp in 0x1f1e6..0x1f1ff
        while (i < text.length) {
            val first = Character.codePointAt(text, i)
            consume()
            if (first == 13 && i < text.length && text[i] == '\n') i++
            else if (regional(first) && i < text.length && regional(Character.codePointAt(text, i))) consume()
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                val category = Character.getType(cp)
                when {
                    category in marks ||
                        cp in 0xfe00..0xfe0f || cp in 0xe0100..0xe01ef || cp in 0x1f3fb..0x1f3ff -> consume()
                    cp == 0x200d && i + 1 < text.length -> { consume(); consume() }
                    else -> break
                }
            }
            ends += i
        }
        return ends.toIntArray()
    }
    fun end(boundaries: IntArray, visible: Int): Int = if (visible <= 0 || boundaries.isEmpty()) 0 else boundaries[minOf(visible, boundaries.size) - 1]
}
