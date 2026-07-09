package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.details?.summary?.title ?: "Details") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (state.details != null) {
                        TextButton(onClick = viewModel::toggleFavorite) {
                            Text(if (state.favorite) "★ Favorite" else "☆ Favorite")
                        }
                        TextButton(onClick = viewModel::toggleSaved) {
                            Text(if (state.saved) "Remove" else "Add to Library")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopStart) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(24.dp))

                state.error != null -> Column(Modifier.padding(24.dp)) {
                    Text(state.error!!)
                    TextButton(onClick = viewModel::load) { Text("Retry") }
                }

                state.details != null -> DetailContent(state.details!!)
            }
        }
    }
}

@Composable
private fun DetailContent(details: MediaDetails) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp) {
                PosterImage(
                    url = details.summary.posterUrl,
                    title = details.summary.title,
                    modifier = Modifier.width(120.dp).height(180.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(details.summary.title, style = MaterialTheme.typography.titleLarge)
                details.summary.year?.let { Text("$it", style = MaterialTheme.typography.bodyMedium) }
                if (details.genres.isNotEmpty()) {
                    Text(details.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                details.summary.rating?.let {
                    Text("★ ${it.toString().take(3)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        details.summary.overview?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        if (details.type == MediaType.TV) {
            Text(
                "Seasons",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            details.seasons.forEach { season ->
                Text(
                    "${season.name} · ${season.episodes.size} episodes",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
        }
    }
}
