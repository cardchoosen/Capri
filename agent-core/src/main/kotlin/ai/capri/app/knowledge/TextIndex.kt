package ai.capri.app.knowledge

import org.json.JSONObject

object TextIndex {
    fun chunks(text: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>(); var body = StringBuilder(); var start = 1; var end = 1
        fun flush() { if (body.isNotEmpty()) result += JSONObject().put("start", start).put("end", end).put("text", body.toString()); body = StringBuilder() }
        text.lineSequence().forEachIndexed { index, line ->
            // Split oversized lines too; citations retain their original line number.
            line.chunked(1000).ifEmpty { listOf("") }.forEach { part ->
                if (body.length + part.length + 1 > 1400) flush()
                if (body.isEmpty()) start = index + 1
                end = index + 1; body.append(part).append('\n')
            }
            if (line.isBlank() && body.length >= 300) flush()
        }
        flush(); return result
    }
    fun score(query: String, text: String): Int {
        val q = query.trim().lowercase(); val hay = text.lowercase()
        val words = Regex("[a-z0-9_-]+|[\\p{IsHan}]+").findAll(q).flatMap { match ->
            if (match.value.any { it.code > 127 } && match.value.length > 1) match.value.windowed(2).asSequence() else sequenceOf(match.value)
        }.toSet()
        return (if (hay.contains(q)) 20 else 0) + words.count { hay.contains(it) } * 2
    }
}
