package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil3.compose.AsyncImage

/**
 * Poster with a text fallback when no image is available or it fails to load.
 *
 * While the image loads, a flat surface-coloured placeholder holds the space;
 * if the load fails (offline, 404, undecodable) the same title-text fallback
 * used for a missing URL takes over, rather than an empty box. [size] picks the
 * TMDB image width to request (see [sizedImageUrl]); the default matches what
 * snapshots already store.
 */
@Composable
fun PosterImage(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    size: PosterSize = PosterSize.Detail,
) {
    val requested = remember(url, size) { url?.takeIf { it.isNotBlank() }?.let { sizedImageUrl(it, size) } }
    var failed by remember(requested) { mutableStateOf(false) }
    if (requested == null || failed) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh)
        AsyncImage(
            model = requested,
            contentDescription = title,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            error = placeholder,
            onError = { failed = true },
        )
    }
}
