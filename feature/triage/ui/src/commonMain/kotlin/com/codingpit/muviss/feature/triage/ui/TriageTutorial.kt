package com.codingpit.muviss.feature.triage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.triage.api.TriageVerdict

/**
 * First-run coaching. Four directions are more than anyone guesses, so the
 * deck says outright what each one does — including what it *saves*, since
 * two of the verdicts write real progress and that should never be a surprise.
 *
 * Shown once per device (`appSettings.triageTutorialSeen`) and re-openable
 * from the header, so dismissing it is never a one-way door.
 */
@Composable
fun TriageTutorial(scheme: TriageControlScheme, onDismiss: () -> Unit) {
    val fourWay = scheme == TriageControlScheme.FOUR_WAY
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("How triage works") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.m)) {
                Text(
                    text = "Swipe the card, tap a button, or use the arrow keys. Z undoes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TriageVerdict.entries.forEach { verdict ->
                    val style = styleFor(verdict, fourWay)
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(style.icon, contentDescription = null, tint = style.color)
                        Column(Modifier.padding(start = MuvissSpacing.m)) {
                            Text("${style.label} · ${style.hint}", style = MaterialTheme.typography.titleSmall)
                            Text(explanationFor(verdict), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(
                    text = "Watching only applies to TV — a film is never partway through.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
