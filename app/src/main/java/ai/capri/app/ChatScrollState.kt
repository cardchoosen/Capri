package ai.capri.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Chronological rows end with a tiny sentinel: fresh layout clamps here and fills backwards. */
internal class ChatScrollState(messageCount: Int) {
    val list = LazyListState(firstVisibleItemIndex = messageCount)
    var followLatest by mutableStateOf(true)
    var userScrollPending by mutableStateOf(false)
    val expandedGroups = mutableStateMapOf<Int, Boolean>()
}
