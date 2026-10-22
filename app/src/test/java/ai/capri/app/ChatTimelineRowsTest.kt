package ai.capri.app

import org.junit.Assert.*
import org.junit.Test

class ChatTimelineRowsTest {
    private fun process() = listOf(Entry("user", "task"), Entry("assistant", "plan"),
        Entry("tool", "list_files\na"), Entry("assistant", "checking"), Entry("tool", "read_file\nb"))
    @Test fun runningToolsStayIndividuallyVisible() {
        val rows = timelineRows(process(), true)
        assertEquals(5, rows.size)
        assertTrue(rows.all { it is TimelineRow.Message })
    }
    @Test fun finalReplyImmediatelyFoldsProcessBeforeJobFinishes() {
        val entries = process() + Entry("assistant", "answer", finalReply = true)
        val rows = timelineRows(entries, true)
        assertEquals(3, rows.size)
        val group = rows[1] as TimelineRow.History
        assertEquals(2, group.toolCount)
        assertEquals(process().drop(1), group.entries)
        assertEquals("answer", (rows.last() as TimelineRow.Message).entry.text)
        assertEquals(5, rows.last().index)
    }
    @Test fun legacyCompletedTurnFoldsButInterruptedTurnStaysVisible() {
        val entries = process() + Entry("assistant", "legacy answer") + process() + Entry("status", "stopped")
        val rows = timelineRows(entries, false)
        assertEquals(1, rows.count { it is TimelineRow.History })
        assertEquals(2, rows.filterIsInstance<TimelineRow.Message>().count { it.entry.role == "tool" })
    }
    @Test fun singleToolDoesNotGetRedundantOuterFold() {
        val entries = listOf(Entry("user", "task"), Entry("tool", "list_files\na"), Entry("assistant", "done", finalReply = true))
        assertTrue(timelineRows(entries, false).all { it is TimelineRow.Message })
    }
    @Test fun oldTurnsStayFoldedWhileNewTurnRuns() {
        val rows = timelineRows(process() + Entry("assistant", "done") + process(), true)
        assertEquals(1, rows.count { it is TimelineRow.History })
        assertEquals(2, rows.filterIsInstance<TimelineRow.Message>().count { it.entry.role == "tool" })
        assertTrue(timelineRows(emptyList(), false).isEmpty())
    }
}
