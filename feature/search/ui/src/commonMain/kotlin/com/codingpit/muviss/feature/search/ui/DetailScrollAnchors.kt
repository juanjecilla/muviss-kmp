package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

/**
 * Where Detail's keyed list items sit, so a control can scroll to one that is not composed yet (#797).
 *
 * A `LazyColumn` scrolls to an index, and an off-screen season header has no index until the items before it are
 * counted. [indexed] counts them as the list is built, and the map is read when the control is tapped.
 */
internal class DetailScrollAnchors {
    val indexByKey = mutableMapOf<String, Int>()
}

/** Runs [block] against this list, recording the index of every item that has a string key. */
internal fun LazyListScope.indexed(anchors: DetailScrollAnchors, block: LazyListScope.() -> Unit) {
    IndexingScope(this, anchors).block()
}

/**
 * Counts what it emits. Only `item` and `items` are overridden: Detail's list uses no sticky headers, so a
 * `stickyHeader` would pass through uncounted, and [indexed] documents that rather than hiding it.
 */
private class IndexingScope(
    private val delegate: LazyListScope,
    private val anchors: DetailScrollAnchors,
) : LazyListScope by delegate {
    private var emitted = 0

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        (key as? String)?.let { anchors.indexByKey[it] = emitted }
        emitted++
        delegate.item(key, contentType, content)
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        emitted += count
        delegate.items(count, key, contentType, itemContent)
    }
}
