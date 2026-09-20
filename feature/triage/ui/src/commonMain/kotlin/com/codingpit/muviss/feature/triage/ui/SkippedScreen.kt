package com.codingpit.muviss.feature.triage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.models.MediaId

@Composable
fun SkippedScreen(
    viewModel: SkippedViewModel,
    onBack: () -> Unit,
    onOpenDetail: (MediaId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(MuvissSpacing.s)) {
            IconButton(onClick = onBack) { Icon(MuvissIcons.Back, contentDescription = "Back") }
            Text("Skipped", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = MuvissSpacing.xs))
        }

        when {
            state.loading -> CircularProgressIndicator(Modifier.padding(MuvissSpacing.xl))

            state.titles.isEmpty() -> EmptyState(
                icon = MuvissIcons.Skip,
                title = "Nothing skipped",
                body = "Titles you skip during triage show up here, in case you change your mind.",
            )

            else -> LazyColumn {
                items(state.titles, key = { it.mediaId.toString() }) { skipped ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // The row opens the title; Restore stays its own
                        // target, so a mis-tap reviews rather than un-skips.
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDetail(skipped.mediaId) }
                            .padding(horizontal = MuvissSpacing.l, vertical = MuvissSpacing.s),
                    ) {
                        PosterImage(
                            url = skipped.posterUrl,
                            title = skipped.title,
                            modifier = Modifier.width(POSTER_WIDTH).height(POSTER_HEIGHT),
                            size = PosterSize.Thumbnail,
                        )
                        Text(
                            text = skipped.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = MuvissSpacing.m),
                        )
                        TextButton(onClick = { viewModel.onRestore(skipped.mediaId) }) { Text("Restore") }
                    }
                }
            }
        }
    }
}

private val POSTER_WIDTH = 48.dp
private val POSTER_HEIGHT = 72.dp
