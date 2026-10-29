package ai.capri.app.coding

/** Deliberately exact line patches: no fuzzy edits or ambiguous context guesses. */
internal object TextPatch {
    data class Hunk(val before: List<String>, val after: List<String>, val operations: List<Pair<Char, String>> = emptyList())
    data class Change(val path: String, val operation: String, val content: String = "", val hunks: List<Hunk> = emptyList())
    fun parse(patch: String): List<Change> {
        val lines = patch.replace("\r\n", "\n").trimEnd('\n').split('\n')
        require(lines.first() == "*** Begin Patch" && lines.last() == "*** End Patch") { "Patch must have Begin/End Patch markers." }
        val changes = mutableListOf<Change>(); var i = 1
        while (i < lines.lastIndex) {
            val header = lines[i++]
            val operation = when { header.startsWith("*** Add File: ") -> "add"; header.startsWith("*** Update File: ") -> "update"; header.startsWith("*** Delete File: ") -> "delete"; else -> errorArgument("Expected an Add/Update/Delete File header.") }
            val path = header.substringAfter(": ")
            require(path.isNotBlank() && path.length <= 1024 && path.none { it.isISOControl() }) { "Invalid patch path." }
            val body = mutableListOf<String>()
            while (i < lines.lastIndex && !lines[i].startsWith("*** ")) body += lines[i++]
            when (operation) {
                "add" -> {
                    require(body.all { it.startsWith('+') }) { "Add File lines must start with +." }
                    changes += Change(path, operation, if (body.isEmpty()) "" else body.joinToString("\n", postfix = "\n") { it.drop(1) })
                }
                "delete" -> { require(body.isEmpty()) { "Delete File cannot include hunks." }; changes += Change(path, operation) }
                else -> {
                    val hunks = mutableListOf<Hunk>(); var at = 0
                    while (at < body.size) {
                        require(body[at] == "@@" || (body[at].startsWith("@@ ") && !body[at].startsWith("@@ -"))) { "Update File needs @@ context hunks, not unified diff line numbers." }; at++
                        val before = mutableListOf<String>(); val after = mutableListOf<String>(); val operations = mutableListOf<Pair<Char, String>>(); var modified = false
                        while (at < body.size && !body[at].startsWith("@@")) {
                            val line = body[at++]
                            require(line.isNotEmpty()) { "Prefix empty context lines with a space." }
                            operations += line[0] to line.drop(1)
                            when (line[0]) {
                                ' ' -> { before += line.drop(1); after += line.drop(1) }
                                '-' -> { before += line.drop(1); modified = true }
                                '+' -> { after += line.drop(1); modified = true }
                                else -> errorArgument("Hunk lines must start with space, + or -.")
                            }
                        }
                        require(before.isNotEmpty() && modified) { "Each hunk needs existing context/removal text and a change." }
                        hunks += Hunk(before, after, operations)
                        require(hunks.size <= 64) { "Too many hunks in one file; split the patch." }
                    }
                    require(hunks.isNotEmpty()) { "Update File needs at least one hunk." }
                    changes += Change(path, operation, hunks = hunks)
                }
            }
            require(changes.size <= 12) { "A patch may affect at most 12 files." }
        }
        require(changes.isNotEmpty()) { "Patch is empty." }
        return changes
    }
    fun apply(original: String, hunks: List<Hunk>, check: () -> Unit = {}): String {
        data class Line(val value: String, val ending: String)
        val bom = if (original.startsWith('\uFEFF')) "\uFEFF" else ""
        val source = original.removePrefix(bom)
        val lines = mutableListOf<Line>(); var start = 0
        source.forEachIndexed { index, c -> if (c == '\n') {
            val cr = index > start && source[index - 1] == '\r'
            require(lines.size < 100000) { "File has too many lines for patching; use a bounded exact edit." }
            if (lines.size % 1024 == 0) check()
            lines += Line(source.substring(start, if (cr) index - 1 else index), if (cr) "\r\n" else "\n"); start = index + 1
        } }
        if (start < source.length) lines += Line(source.substring(start), "")
        val defaultEnding = lines.firstOrNull { it.ending.isNotEmpty() }?.ending ?: "\n"
        var floor = 0; var comparisons = 0
        for (hunk in hunks) {
            check(); val positions = mutableListOf<Int>()
            for (index in floor..(lines.size - hunk.before.size)) {
                check()
                if (hunk.before.indices.all { require(++comparisons <= 2000000) { "Context search exceeded the patch budget; narrow the edit." }; lines[index + it].value == hunk.before[it] }) positions += index
                if (positions.size > 1) break
            }
            require(positions.size == 1) { if (positions.isEmpty()) "Patch context not found. Read current file content." else "Patch context is ambiguous. Include more surrounding lines." }
            val at = positions.single(); val removed = lines.subList(at, at + hunk.before.size).toList()
            val localEnding = removed.firstOrNull { it.ending.isNotEmpty() }?.ending ?: defaultEnding
            val ending = removed.last().ending
            val replacement = if (hunk.operations.isEmpty()) hunk.after.map { Line(it, localEnding) }.toMutableList()
            else mutableListOf<Line>().apply {
                var position = 0
                for ((operation, value) in hunk.operations) when (operation) {
                    ' ' -> add(removed[position++])
                    '-' -> position++
                    '+' -> add(Line(value, localEnding))
                }
            }
            for (index in 0 until replacement.lastIndex) if (replacement[index].ending.isEmpty()) replacement[index] = replacement[index].copy(ending = localEnding)
            if (replacement.isNotEmpty()) replacement[replacement.lastIndex] = replacement.last().copy(ending = ending)
            lines.subList(at, at + hunk.before.size).clear(); lines.addAll(at, replacement); floor = at + replacement.size
        }
        return bom + lines.joinToString("") { it.value + it.ending }
    }
    private fun errorArgument(message: String): Nothing = throw IllegalArgumentException(message)
}
