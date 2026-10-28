package ai.capri.app.terminal

/** Keep incomplete UTF-8 suffix bytes for the next read, independent of Android decoder behavior. */
internal object TerminalOutput {
    data class Chunk(val text: String, val consumed: Int)
    fun bounded(bytes: ByteArray, endOfInput: Boolean, jsonBudget: Int = 24000): Chunk {
        require(jsonBudget >= 8)
        val full = decode(bytes, endOfInput)
        if (org.json.JSONObject.quote(full.text).length <= jsonBudget) return full
        var low = 0; var high = bytes.size
        while (low < high) {
            val middle = (low + high + 1) / 2
            val candidate = decode(bytes.copyOf(middle), endOfInput && middle == bytes.size)
            if (org.json.JSONObject.quote(candidate.text).length <= jsonBudget) low = middle else high = middle - 1
        }
        return decode(bytes.copyOf(low), endOfInput && low == bytes.size)
    }
    fun decode(bytes: ByteArray, endOfInput: Boolean): Chunk {
        var end = bytes.size
        if (!endOfInput && end > 0) {
            var start = end - 1
            while (start > 0 && (bytes[start].toInt() and 0xc0) == 0x80) start--
            val lead = bytes[start].toInt() and 0xff
            val expected = when (lead) {
                in 0xc2..0xdf -> 2
                in 0xe0..0xef -> 3
                in 0xf0..0xf4 -> 4
                else -> 1
            }
            if (end - start < expected) end = start
        }
        return Chunk(String(bytes, 0, end, Charsets.UTF_8), end)
    }
}
