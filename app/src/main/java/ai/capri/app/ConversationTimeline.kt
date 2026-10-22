package ai.capri.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TimelineMuted = Color(0xFF939E94)

@Composable internal fun ConversationTimeline(entries: List<Entry>, busy: Boolean, position: ChatScrollState, modifier: Modifier = Modifier) {
    val rows = remember(entries, busy) { timelineRows(entries, busy) }
    val list = position.list
    val input = remember(position) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) {
                position.followLatest = false
                position.userScrollPending = true
            }
            return Offset.Zero
        }
    } }
    LaunchedEffect(position) {
        snapshotFlow {
            val layout = list.layoutInfo
            // Observe remeasure as prose/code/table heights grow, not the reveal clock.
            listOf(position.followLatest, position.userScrollPending, list.isScrollInProgress, list.canScrollForward,
                layout.totalItemsCount, layout.viewportEndOffset,
                layout.visibleItemsInfo.map { Triple(it.key, it.offset, it.size) })
        }.collect {
            if (!list.isScrollInProgress) {
                if (position.userScrollPending) {
                    position.userScrollPending = false
                    position.followLatest = !list.canScrollForward
                }
                if (position.followLatest && list.canScrollForward && list.layoutInfo.totalItemsCount > 0)
                    list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
            }
        }
    }
    LazyColumn(state = list, modifier = modifier.nestedScroll(input), verticalArrangement = Arrangement.Bottom,
        contentPadding = PaddingValues(16.dp)) {
        items(rows, key = { "message:${it.index}" }) { row ->
            Column {
                if (row.index > 0) Spacer(Modifier.height(if (row is TimelineRow.Message && row.entry.role == "tool") 6.dp else 18.dp))
                when (row) {
                    is TimelineRow.Message -> TimelineMessage(row.entry)
                    is TimelineRow.History -> {
                        val expanded = position.expandedGroups[row.index] == true
                        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp)
                            .clickable(role = Role.Button, onClickLabel = if (expanded) "收起本轮工具记录" else "展开本轮工具记录") {
                                position.followLatest = false
                                position.expandedGroups[row.index] = !expanded
                            }.semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("工具记录 · ${row.toolCount} 条", color = TimelineMuted, fontSize = 13.sp, lineHeight = 20.sp)
                            Spacer(Modifier.width(4.dp))
                            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                null, tint = TimelineMuted, modifier = Modifier.size(16.dp))
                        }
                        if (expanded) Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.entries.forEachIndexed { i, entry -> key(row.index + i) { TimelineMessage(entry) } }
                        }
                    }
                }
            }
        }
        // A tiny real last item anchors initial layout and explicit following at the bottom.
        item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
    }
}

@Composable private fun TimelineMessage(message: Entry) {
    if (message.role == "tool") ToolOutputRow(message.text)
    else Column(Modifier.fillMaxWidth().then(if (message.role == "user")
        Modifier.background(Color(0xFF111612), RoundedCornerShape(2.dp)).padding(12.dp) else Modifier)) {
        Text(when (message.role) { "user" -> "YOU"; "assistant" -> "CAPRI"; else -> "SYSTEM" },
            color = when (message.role) { "assistant" -> Color(0xFFA5FF75); "status" -> Color(0xFFFF514F); else -> TimelineMuted },
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        if (message.role == "assistant") MarkdownReply(message.text, message.reveal)
        else SelectionContainer { Text(message.text, fontSize = 15.sp, color = Color(0xFFF4F6F3), lineHeight = 24.sp) }
    }
}
