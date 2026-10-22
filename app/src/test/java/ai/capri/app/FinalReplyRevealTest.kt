package ai.capri.app

import org.junit.Assert.*
import org.junit.Test

class FinalReplyRevealTest {
    @Test fun revealProgressIsMonotonicBoundedAndUsesElapsedTimeWithoutReplay() {
        val reveal = FinalReplyReveal(0)
        assertEquals(0, reveal.visibleUnits(100, 0))
        assertEquals(50, reveal.visibleUnits(100, 900_000_000))
        assertEquals(100, reveal.visibleUnits(100, 1_800_000_000))
        assertEquals(100, reveal.visibleUnits(100, 60_000_000_000))
        assertEquals(10000, FinalReplyReveal(0).visibleUnits(10000, 8_000_000_000))
        assertEquals(0, reveal.visibleUnits(0, 0))
    }
    @Test fun finishOverridesClockBeforeDeadline() {
        val reveal = FinalReplyReveal(0)
        assertEquals(0, reveal.visibleUnits(100, 0))
        reveal.finish()
        assertEquals(100, reveal.visibleUnits(100, 0))
        assertEquals(100, reveal.visibleUnits(100, 1))
    }
    @Test fun unicodeChunksPreserveEmojiFlagsModifiersCombiningMarksAndLineEndings() {
        val pieces = listOf("摩", "羯", "😀", "👩🏽‍💻", "👨‍👩‍👧‍👦", "🇨🇳", "e\u0301", "\r\n", "末")
        val text = pieces.joinToString("")
        val boundaries = RevealText.boundaries(text)
        assertEquals(pieces.size, boundaries.size)
        for (i in pieces.indices) assertEquals(pieces.take(i + 1).joinToString(""), text.substring(0, RevealText.end(boundaries, i + 1)))
        assertEquals(0, RevealText.end(intArrayOf(), 5))
        assertEquals(text.length, RevealText.end(boundaries, 9999))
    }
}
