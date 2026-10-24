package ai.capri.app

import ai.capri.app.knowledge.TextIndex
import org.junit.Assert.*
import org.junit.Test

class KnowledgeTest {
    @Test fun chineseEnglishAndNoMatch() {
        assertTrue(TextIndex.score("长期记忆", "需要管理长期记忆和作用域") > 0)
        assertTrue(TextIndex.score("PIXEL api", "Pixel device API") > 0)
        assertEquals(0, TextIndex.score("完全无关", "Build a Kotlin project"))
    }
    @Test fun chunksKeepLinesAndBoundHugeLines() {
        val chunks = TextIndex.chunks("第一行\n" + "文".repeat(6000) + "\n最后一行")
        assertTrue(chunks.size > 4)
        assertTrue(chunks.all { it.getString("text").length <= 1400 })
        assertEquals(1, chunks.first().getInt("start"))
        assertEquals(3, chunks.last().getInt("end"))
        assertEquals(6000, chunks.sumOf { c -> c.getString("text").count { it == '文' } })
    }
}
