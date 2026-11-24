package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.component.sizedImageUrl
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.image_name_backdrop
import com.codingpit.muviss.feature.search.ui.generated.resources.image_name_poster
import com.codingpit.muviss.feature.search.ui.generated.resources.image_name_still
import com.codingpit.muviss.feature.search.ui.generated.resources.image_open_backdrop
import com.codingpit.muviss.feature.search.ui.generated.resources.image_open_poster
import com.codingpit.muviss.feature.search.ui.generated.resources.image_open_still
import com.codingpit.muviss.models.ImageKind
import org.jetbrains.compose.resources.stringResource

internal const val DETAIL_BACKDROP_TAG = "detailBackdrop"
internal const val DETAIL_POSTER_TAG = "detailPoster"

/**
 * The URL that [com.codingpit.muviss.core.designsystem.component.PosterImage] requests for [url] at [size], or null
 * when there is no artwork. The viewer must open on exactly this URL: Coil caches by URL, so only then is the picture
 * already on the device when the viewer opens offline (the stored `w500` is a different entry from the grid's `w342`).
 */
internal fun drawnImageUrl(url: String?, size: PosterSize): String? = url?.takeIf { it.isNotBlank() }?.let { sizedImageUrl(it, size) }

/**
 * Makes artwork open the Image Viewer on [kind]'s Gallery, seeded with [url]. Artwork the source
 * has none of ([url] null) is left alone: there is nothing to look at full screen.
 *
 * The control has two labels and both matter. Its content description is the name a screen reader gives the
 * button (the picture under it has none, so without this it is an unnamed button); its click label says what
 * the activation does.
 */
@Composable
internal fun Modifier.opensImageViewer(url: String?, kind: ImageKind, onOpen: (ImageKind, String) -> Unit): Modifier {
    if (url.isNullOrBlank()) return this
    val action = stringResource(
        when (kind) {
            ImageKind.Backdrop -> Res.string.image_open_backdrop
            ImageKind.Poster -> Res.string.image_open_poster
            ImageKind.Still -> Res.string.image_open_still
        },
    )
    val name = stringResource(
        when (kind) {
            ImageKind.Backdrop -> Res.string.image_name_backdrop
            ImageKind.Poster -> Res.string.image_name_poster
            ImageKind.Still -> Res.string.image_name_still
        },
    )
    return clickable(onClickLabel = action, role = Role.Button) { onOpen(kind, url) }
        .semantics { contentDescription = name }
}
