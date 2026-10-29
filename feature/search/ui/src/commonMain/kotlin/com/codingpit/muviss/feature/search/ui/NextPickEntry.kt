package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.next_pick_entry
import org.jetbrains.compose.resources.stringResource

/** Test tag of the "What's next?" entry on Discover and on a finished title's Detail (EPIC 51, #545). */
const val NEXT_PICK_ENTRY_TAG = "nextPickEntry"

/**
 * The way into the Pick Session from this feature's screens (EPIC 51, #545). A plain labelled button, not an
 * icon: the words say what it opens, and the 48dp floor is the minimum touch target.
 */
@Composable
internal fun NextPickEntryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(stringResource(Res.string.next_pick_entry), style = MaterialTheme.typography.labelLarge)
    }
}
