package ai.capri.app.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalOutputTest {
    @Test fun everyChunkSizePreservesChineseAndSupplementaryCharacters() {
        val original = "摩羯😀 Capri\r\n"
        val bytes = original.toByteArray(Charsets.UTF_8)
        for (limit in 4..bytes.size) {
            var offset = 0
            val rebuilt = StringBuilder()
            while (offset < bytes.size) {
                val end = minOf(offset + limit, bytes.size)
                val chunk = TerminalOutput.decode(bytes.copyOfRange(offset, end), end == bytes.size)
                assertTrue(chunk.consumed > 0)
                rebuilt.append(chunk.text)
                offset += chunk.consumed
            }
            assertEquals(original, rebuilt.toString())
        }
    }
    @Test fun incompleteSuffixWaitsUntilNextReadButMalformedFinalBytesMakeProgress() {
        val bytes = "摩羯".toByteArray(Charsets.UTF_8).copyOf(4)
        val partial = TerminalOutput.decode(bytes, false)
        assertEquals("摩", partial.text)
        assertEquals(3, partial.consumed)
        assertEquals(4, TerminalOutput.decode(bytes, true).consumed)
        assertEquals(0, TerminalOutput.decode(byteArrayOf(), false).consumed)
        assertEquals(1, TerminalOutput.decode(byteArrayOf(0x80.toByte()), false).consumed)
    }
}
