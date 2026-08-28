package com.codingpit.muviss.feature.collection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.PosterCard
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.collection.domain.MediaListItem
import com.codingpit.muviss.models.MediaId

/**
 * One list's contents (EPIC 17): a poster grid, mirroring the Library
 * grid's layout, plus a per-title remove-from-list affordance. Entries
 * whose title has no live library snapshot are simply absent here (see
 * `ListsRepository`'s KDoc) rather than rendered as a broken tile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListContentsScreen(
    name: String,
    viewModel: ListContentsViewModel,
    onBack: () -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MuvissIcons.Back, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(top = MuvissSpacing.xxl))
                state.error != null -> Text(state.error!!, modifier = Modifier.padding(top = MuvissSpacing.xxl), style = MaterialTheme.typography.bodyMedium)
                state.items.isEmpty() -> EmptyListContentsState()
                else -> ListContentsGrid(state.items, onOpenDetail, onRemove = viewModel::removeEntry)
            }
        }
    }
}

@Composable
private fun EmptyListContentsState() {
    EmptyState(
        icon = MuvissIcons.AddToList,
        title = "Nothing in this list yet",
        body = "Add a title from its detail screen.",
    )
}

@Composable
private fun ListContentsGrid(
    items: List<MediaListItem>,
    onOpenDetail: (MediaId) -> Unit,
    onRemove: (MediaId) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        contentPadding = PaddingValues(
            start = MuvissSpacing.m,
            end = MuvissSpacing.m,
            top = MuvissSpacing.m,
            bottom = MuvissSpacing.bottomContent,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.mediaId.toString() }) { item ->
            Column {
                PosterCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    onClick = { onOpenDetail(item.mediaId) },
                )
                TextButton(onClick = { onRemove(item.mediaId) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Remove", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
