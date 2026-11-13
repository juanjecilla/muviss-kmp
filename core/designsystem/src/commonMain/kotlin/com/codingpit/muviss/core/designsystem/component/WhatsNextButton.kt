package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The "What's next?" entry into the Pick Session (EPIC 51, #545, #659): a plain outlined button whose
 * words say what it opens, with the 48 dp floor as its touch target.
 *
 * Shared by Discover, a finished title's Detail, Progress and Library. The label is the caller's, so no
 * feature's string resources have to cross into this module.
 */
@Composable
fun WhatsNextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
