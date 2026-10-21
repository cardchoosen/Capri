package ai.capri.app

import androidx.compose.foundation.lazy.LazyListState

/** Kept outside the tab content, in memory only: a fresh app opens at the latest message. */
internal class ChatScrollState(var messageCount: Int) {
    // The timeline is reversed, so index zero is already the bottom, even for a tall reply.
    val list = LazyListState()
    var followLatest = true
}
