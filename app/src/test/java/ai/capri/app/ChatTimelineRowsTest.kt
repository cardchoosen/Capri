package ai.capri.app

import org.junit.Assert.*
import org.junit.Test

class ChatTimelineRowsTest {
    private fun process() = listOf(Entry("user", "task"), Entry("assistant", "plan"),
        Entry("tool", "list_files\na"), Entry("assistant", "checking"), Entry("tool", "read_file\nb"))
    @Test fun runningToolsAreFoldedAndRetainAllRecords() {
        val rows = timelineRows(process(), true)
        assertEquals(2, rows.size)
        val group = rows[1] as TimelineRow.History
        assertTrue(group.running)
        assertEquals(process().drop(1), group.entries)
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
    @Test fun legacyAndInterruptedToolsFoldButFailureStatusStaysVisible() {
        val entries = process() + Entry("assistant", "legacy answer") + process() + Entry("status", "stopped")
        val rows = timelineRows(entries, false)
        assertEquals(2, rows.count { it is TimelineRow.History })
        assertEquals("status", (rows.last() as TimelineRow.Message).entry.role)
    }
    @Test fun singleToolAlsoUsesTheUnifiedHistoryRow() {
        val entries = listOf(Entry("user", "task"), Entry("tool", "list_files\na"), Entry("assistant", "done", finalReply = true))
        assertEquals(1, timelineRows(entries, false).count { it is TimelineRow.History })
    }
    @Test fun oldTurnsStayFoldedWhileNewTurnRuns() {
        val rows = timelineRows(process() + Entry("assistant", "done") + process(), true)
        assertEquals(2, rows.count { it is TimelineRow.History })
        assertTrue((rows.last() as TimelineRow.History).running)
        assertTrue(timelineRows(emptyList(), false).isEmpty())
    }
    @Test fun activeToolShowsBeforeAnyResultAndAttachmentsNeverHideInHistory() {
        val active = ToolActivity("call", "读取文件")
        val pending = timelineRows(listOf(Entry("user", "task")), true, active)
        assertEquals(active, (pending.last() as TimelineRow.History).activity)
        val entries = process() + Entry("attachment", "image metadata") + Entry("assistant", "done", finalReply = true)
        val rows = timelineRows(entries, false)
        assertTrue(rows.any { it is TimelineRow.Message && it.entry.role == "attachment" })
        assertFalse(rows.filterIsInstance<TimelineRow.History>().single().entries.any { it.role == "attachment" })
    }
}
