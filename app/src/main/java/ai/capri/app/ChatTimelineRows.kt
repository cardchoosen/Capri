package ai.capri.app

internal sealed interface TimelineRow {
    val index: Int
    data class Message(override val index: Int, val entry: Entry) : TimelineRow
    data class History(override val index: Int, val entries: List<Entry>, val toolCount: Int) : TimelineRow
}

/** Only presentation changes. Recorded messages and the model's tool results stay intact. */
internal fun timelineRows(entries: List<Entry>, busy: Boolean): List<TimelineRow> {
    val rows = mutableListOf<TimelineRow>()
    var start = 0
    while (start < entries.size) {
        val nextUser = (start + 1 until entries.size).firstOrNull { entries[it].role == "user" } ?: entries.size
        val turn = entries.subList(start, nextUser)
        val explicitFinal = turn.indexOfLast { it.role == "assistant" && it.finalReply }
        // Migrate old history without changing the saved text. Failed/cancelled turns
        // end in a status entry and are deliberately left visible.
        val legacyFinal = if (explicitFinal < 0 && (!busy || nextUser < entries.size) && turn.last().role == "assistant") turn.lastIndex else -1
        val final = if (explicitFinal >= 0) explicitFinal else legacyFinal
        val processStart = if (turn.first().role == "user") 1 else 0
        val process = if (final > processStart) turn.subList(processStart, final) else emptyList()
        val count = process.count { it.role == "tool" }
        if (count >= 2) {
            if (processStart == 1) rows += TimelineRow.Message(start, turn.first())
            rows += TimelineRow.History(start + processStart, process.toList(), count)
            for (i in final until turn.size) rows += TimelineRow.Message(start + i, turn[i])
        } else turn.forEachIndexed { i, entry -> rows += TimelineRow.Message(start + i, entry) }
        start = nextUser
    }
    return rows
}
