package ai.capri.app

internal sealed interface TimelineRow {
    val index: Int
    data class Message(override val index: Int, val entry: Entry) : TimelineRow
    data class History(override val index: Int, val entries: List<Entry>, val toolCount: Int, val running: Boolean = false, val activity: ToolActivity? = null) : TimelineRow
}

/** Only presentation changes. Recorded messages and the model's tool results stay intact. */
internal fun timelineRows(entries: List<Entry>, busy: Boolean, activity: ToolActivity? = null): List<TimelineRow> {
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
        val running = busy && nextUser == entries.size && final < 0
        val processEnd = if (final >= 0) final else turn.size
        val process = (processStart until processEnd).filter { turn[it].role !in setOf("attachment", "status") }
        val count = process.count { turn[it].role == "tool" }
        if (count > 0 || running && activity != null) {
            if (processStart == 1) rows += TimelineRow.Message(start, turn.first())
            rows += TimelineRow.History(if (process.isNotEmpty()) start + process.first() else start + turn.size, process.map { turn[it] }, count, running, if (running) activity else null)
            for (i in processStart until processEnd) if (turn[i].role in setOf("attachment", "status"))
                rows += TimelineRow.Message(start + i, turn[i])
            if (final >= 0) for (i in final until turn.size) rows += TimelineRow.Message(start + i, turn[i])
        } else turn.forEachIndexed { i, entry -> rows += TimelineRow.Message(start + i, entry) }
        start = nextUser
    }
    return rows
}
