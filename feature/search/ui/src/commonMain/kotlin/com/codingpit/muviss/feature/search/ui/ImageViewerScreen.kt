package com.codingpit.muviss.feature.search.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.layout.LightStatusBarIcons
import com.codingpit.muviss.core.designsystem.layout.extendIntoTopInset
import com.codingpit.muviss.core.designsystem.layout.immersiveTopPadding
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.action_close
import com.codingpit.muviss.feature.search.ui.generated.resources.image_viewer_backdrop
import com.codingpit.muviss.feature.search.ui.generated.resources.image_viewer_position
import com.codingpit.muviss.feature.search.ui.generated.resources.image_viewer_poster
import com.codingpit.muviss.feature.search.ui.generated.resources.image_viewer_still
import com.codingpit.muviss.models.ImageKind
import com.codingpit.muviss.models.TitleImage
import kotlinx.coroutines.launch
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import org.jetbrains.compose.resources.stringResource

internal const val IMAGE_VIEWER_TAG = "imageViewer"
internal const val IMAGE_VIEWER_POSITION_TAG = "imageViewerPosition"
internal const val IMAGE_VIEWER_CLOSE_TAG = "imageViewerClose"

/**
 * The Image Viewer: a title's (or an episode's) artwork full screen, one image per page.
 *
 * Swipe sideways to page, pinch or double-tap to zoom, swipe down to leave, tap to hide the
 * controls. Where there is no touch screen the same things are on the keyboard and mouse: arrow
 * keys page, Escape closes, the mouse wheel zooms.
 */
@Composable
fun ImageViewerScreen(viewModel: ImageViewerViewModel, onClose: () -> Unit) {
    val images by viewModel.images.collectAsStateWithLifecycle()
    ImageViewerContent(images, viewModel.kind, onClose)
}

@Composable
internal fun ImageViewerContent(images: List<TitleImage>, kind: ImageKind, onClose: () -> Unit) {
    // The viewer is black edge to edge, so the status bar is drawn over black: light icons, whatever the theme.
    LightStatusBarIcons()
    // Raw, because ScreenInsets already consumed the top for this subtree; see immersiveTopPadding. The viewer
    // extends up into that band so its black runs under the status bar, and its controls clear it by hand.
    val topInset = immersiveTopPadding()
    val pager = rememberPagerState { images.size }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(true) }
    val flick = rememberFlickToDismissState()

    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .extendIntoTopInset(topInset)
            .background(Color.Black)
            .testTag(IMAGE_VIEWER_TAG)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> onClose().let { true }
                    Key.DirectionRight -> scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(images.lastIndex)) }.let { true }
                    Key.DirectionLeft -> scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }.let { true }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        DismissOnFlick(flick, onClose) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { images[it].fileKey }) { page ->
                GalleryPage(
                    image = images[page],
                    description = pageDescription(kind, page + 1, images.size),
                    onTap = { controlsVisible = !controlsVisible },
                )
            }
        }

        AnimatedVisibility(controlsVisible, Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = topInset).padding(12.dp).size(36.dp).testTag(IMAGE_VIEWER_CLOSE_TAG),
            ) {
                IconButton(onClick = onClose) {
                    Icon(MuvissIcons.Close, contentDescription = stringResource(Res.string.action_close), tint = Color.White)
                }
            }
        }
        if (images.size > 1) {
            AnimatedVisibility(controlsVisible, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                Text(
                    stringResource(Res.string.image_viewer_position, pager.currentPage + 1, images.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(top = topInset + 20.dp).testTag(IMAGE_VIEWER_POSITION_TAG),
                )
            }
        }
    }
}

/**
 * Leaves the viewer once the person flicks the image far enough up or down.
 *
 * `gestureState` is the only way to learn the flick finished, and telephoto marks it experimental.
 */
@OptIn(ExperimentalTelephotoApi::class)
@Composable
private fun DismissOnFlick(state: FlickToDismissState, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    FlickToDismiss(state = state, modifier = Modifier.fillMaxSize()) { content() }
    LaunchedEffect(state.gestureState) {
        if (state.gestureState is FlickToDismissState.GestureState.Dismissed) onDismiss()
    }
}

@Composable
private fun GalleryPage(image: TitleImage, description: String, onTap: () -> Unit) {
    val zoom = rememberZoomableState(ZoomSpec(maxZoomFactor = MAX_ZOOM))
    AsyncImage(
        model = image.url,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        // Telephoto needs the picture's own size to know where its edges are.
        onState = { state -> zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(state.painter?.intrinsicSize)) },
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
            .zoomable(zoom, onClick = { onTap() }),
    )
}

@Composable
private fun pageDescription(kind: ImageKind, position: Int, total: Int): String = when (kind) {
    ImageKind.Backdrop -> stringResource(Res.string.image_viewer_backdrop, position, total)
    ImageKind.Poster -> stringResource(Res.string.image_viewer_poster, position, total)
    ImageKind.Still -> stringResource(Res.string.image_viewer_still, position, total)
}

private const val MAX_ZOOM = 4f
