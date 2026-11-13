package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Says that what the user is doing here will not be saved.
 *
 * Only web ever shows this. Every other platform's database is a file the app
 * owns, so the caller passes a message of `null` and nothing is drawn — the
 * banner takes a nullable string rather than a `PersistenceState` so
 * `:core:designsystem` does not have to depend on `:core:database` to render
 * one line of text.
 *
 * **Not dismissible, on purpose.** It describes a condition that stays true for
 * as long as the tab is open, not an event that has passed; an × would hide the
 * only warning the user gets, and the tab that needs the warning is precisely
 * the one with nowhere durable to remember the dismissal.
 *
 * One line, tight padding: it sits above the whole `NavHost` and every vertical
 * pixel it takes is taken from every screen.
 *
 * `liveRegion` so a screen reader announces it when it appears rather than only
 * when focus happens to land on it — it can appear after the page has settled,
 * once the lock election resolves.
 *
 * [actionLabel] is the one way out, when there is one: reloading a tab that is
 * not saving (#62). It is absent for the warnings with nothing to do about them,
 * and the banner keeps its one-line shape without it. Dismissing is still not
 * offered; the action is the way the warning goes away.
 */
@Composable
fun PersistenceBanner(
    message: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    if (message == null) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
