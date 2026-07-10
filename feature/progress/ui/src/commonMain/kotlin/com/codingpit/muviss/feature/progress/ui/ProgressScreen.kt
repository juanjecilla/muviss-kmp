package com.codingpit.muviss.feature.progress.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.models.MediaId

@Composable
fun ProgressScreen(
    viewModel: ProgressViewModel,
    onOpenDetail: (MediaId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.padding(top = 32.dp))

            state.error != null -> Text(
                state.error!!,
                modifier = Modifier.padding(top = 32.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            state.items.isEmpty() -> Text(
                "Nothing to watch next — start something from your Library",
                modifier = Modifier.padding(top = 32.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> WatchNextList(state.items, onTick = viewModel::tickNext, onOpenDetail = onOpenDetail)
        }
    }
}

@Composable
private fun WatchNextList(
    items: List<WatchNextItem>,
    onTick: (WatchNextItem) -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        items(items, key = { it.mediaId.toString() }) { item ->
            WatchNextRow(item, onTick = { onTick(item) }, onClick = { onOpenDetail(item.mediaId) })
        }
    }
}

@Composable
private fun WatchNextRow(
    item: WatchNextItem,
    onTick: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(onClick = onClick).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PosterImage(
                url = item.posterUrl,
                title = item.title,
                modifier = Modifier.width(56.dp).height(84.dp).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val episode = item.nextEpisode
                if (episode != null) {
                    Text(
                        "S${episode.seasonNumber}E${episode.episodeNumber} · ${episode.name}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text("Caught up — waiting on new episodes", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (item.nextEpisode != null) {
                Button(onClick = onTick) { Text("Mark seen") }
            }
        }
    }
}
