package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.image_open_backdrop
import com.codingpit.muviss.feature.search.ui.generated.resources.image_open_poster
import com.codingpit.muviss.feature.search.ui.generated.resources.image_open_still
import com.codingpit.muviss.models.ImageKind
import org.jetbrains.compose.resources.stringResource

internal const val DETAIL_BACKDROP_TAG = "detailBackdrop"
internal const val DETAIL_POSTER_TAG = "detailPoster"

/**
 * Makes artwork open the Image Viewer on [kind]'s Gallery, seeded with [url]. Artwork the source
 * has none of ([url] null) is left alone: there is nothing to look at full screen.
 */
@Composable
internal fun Modifier.opensImageViewer(url: String?, kind: ImageKind, onOpen: (ImageKind, String) -> Unit): Modifier {
    if (url.isNullOrBlank()) return this
    val label = stringResource(
        when (kind) {
            ImageKind.Backdrop -> Res.string.image_open_backdrop
            ImageKind.Poster -> Res.string.image_open_poster
            ImageKind.Still -> Res.string.image_open_still
        },
    )
    return clickable(onClickLabel = label, role = Role.Button) { onOpen(kind, url) }
}
