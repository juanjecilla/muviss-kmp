package com.codingpit.muviss.feature.search.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableState
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
    ImageViewerContent(images, viewModel.kind, onClose, seed = viewModel.seed)
}

@Composable
internal fun ImageViewerContent(
    images: List<TitleImage>,
    kind: ImageKind,
    onClose: () -> Unit,
    /** The image the person tapped; the first page is swapped for the source's larger copy of it, and falls back to it. */
    seed: TitleImage? = null,
    /** Reports the size of the picture each page draws, whenever it changes. Tests read it; the viewer does not. */
    onPageSize: (TitleImage, Size?) -> Unit = { _, _ -> },
) {
    // The viewer is black edge to edge, so the status bar is drawn over black: light icons, whatever the theme.
    LightStatusBarIcons()
    // Escape auto-repeats, and the close button or a flick can land in the same frame; the owner pops the back stack on
    // each call, so the second one would pop the screen underneath too. Leaving is requested once.
    var leaving by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            onClose()
        }
    }
    // Raw, because ScreenInsets already consumed the top for this subtree; see immersiveTopPadding. The viewer
    // extends up into that band so its black runs under the status bar, and its controls clear it by hand.
    val topInset = immersiveTopPadding()
    val pager = rememberPagerState { images.size }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(true) }
    val flick = rememberFlickToDismissState()
    // The zoom state of each page on screen, by file: the arrow keys pan the picture in front of the person when it
    // is zoomed in, and turn the page only when it is not.
    val zooms = remember { mutableMapOf<String, ZoomableState>() }
    val panStep = with(LocalDensity.current) { KEY_PAN_STEP.toPx() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .extendIntoTopInset(topInset)
            .background(Color.Black)
            .testTag(IMAGE_VIEWER_TAG)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val zoom = zooms[images.getOrNull(pager.currentPage)?.fileKey]
                val zoomed = zoom != null && (zoom.zoomFraction ?: 0f) > 0f
                when (event.key) {
                    Key.Escape -> leave().let { true }

                    Key.DirectionRight -> if (zoomed) {
                        pan(scope, zoom, Offset(-panStep, 0f))
                    } else {
                        scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(images.lastIndex)) }.let { true }
                    }

                    Key.DirectionLeft -> if (zoomed) {
                        pan(scope, zoom, Offset(panStep, 0f))
                    } else {
                        scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }.let { true }
                    }

                    Key.DirectionDown -> zoomed && pan(scope, zoom, Offset(0f, -panStep))

                    Key.DirectionUp -> zoomed && pan(scope, zoom, Offset(0f, panStep))

                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        DismissOnFlick(flick, leave) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { images[it].fileKey }) { page ->
                GalleryPage(
                    seed = seed,
                    image = images[page],
                    description = pageDescription(kind, page + 1, images.size),
                    zooms = zooms,
                    onTap = { controlsVisible = !controlsVisible },
                    onPageSize = onPageSize,
                )
            }
        }

        AnimatedVisibility(controlsVisible, Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = topInset).padding(12.dp).size(36.dp).testTag(IMAGE_VIEWER_CLOSE_TAG),
            ) {
                IconButton(onClick = leave) {
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
                    // Polite, so a screen reader says the new position when the person pages, without interrupting them.
                    modifier = Modifier
                        .padding(top = topInset + 20.dp)
                        .testTag(IMAGE_VIEWER_POSITION_TAG)
                        .semantics { liveRegion = LiveRegionMode.Polite },
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
private fun GalleryPage(
    image: TitleImage,
    description: String,
    seed: TitleImage?,
    zooms: MutableMap<String, ZoomableState>,
    onTap: () -> Unit,
    onPageSize: (TitleImage, Size?) -> Unit,
) {
    val zoom = rememberZoomableState(ZoomSpec(maxZoomFactor = MAX_ZOOM))
    DisposableEffect(image.fileKey) {
        zooms[image.fileKey] = zoom
        onDispose { zooms.remove(image.fileKey) }
    }
    // A replaced first page draws the tapped image while the source's larger copy is not on screen: before it loads,
    // and if it fails. The tapped image is in the image cache already, so it is drawn at once. The choice itself is
    // [shownImage]; this only loads both and draws what it picks. Nothing else falls back.
    val tappedUrl = seed?.url?.takeIf { image.replacesSeed(seed) }
    val replacement = rememberAsyncImagePainter(model = image.url, contentScale = ContentScale.Fit)
    val tapped = rememberAsyncImagePainter(model = tappedUrl, contentScale = ContentScale.Fit)
    // Collecting the states is what recomposes this page when a load finishes; the sizes are read from the same loads.
    val replacementLoaded = replacement.state.collectAsState().value is AsyncImagePainter.State.Success
    val tappedLoaded = tapped.state.collectAsState().value is AsyncImagePainter.State.Success
    val shown = shownImage(replacesSeed = tappedUrl != null, replacementLoaded = replacementLoaded, tappedLoaded = tappedLoaded)
    val painter = if (shown == ShownImage.Tapped) tapped else replacement
    // Telephoto needs the size of what is drawn, and the bounds of what Fit draws, to know where its edges are. It
    // changes with the picture on screen, so it is reported every time it does, including once the tapped image loads
    // after a replacement has already failed.
    val size = shownSize(
        shown,
        replacementSize = replacement.intrinsicSize.takeIf { replacementLoaded },
        tappedSize = tapped.intrinsicSize.takeIf { tappedLoaded },
    )
    LaunchedEffect(size) {
        zoom.setContentLocation(fitLocation(size))
        onPageSize(image, size)
    }
    Image(
        painter = painter,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .testTag(imageViewerImageTag(shown))
            // Before zoomable: the wheel's focal point must be in viewport coordinates, which only the untransformed
            // node has. Zoomable's own shortcut (Alt + wheel) runs in the Main pass and consumes its scrolls, so the
            // Final pass here sees only the scrolls nobody else took.
            .wheelZooms(zoom)
            .zoomable(zoom, onClick = { onTap() }),
    )
}

/**
 * Zooms on an unmodified mouse wheel, through [WheelZoomQueue], so quick scrolls compound.
 *
 * Telephoto's own shortcut zooms on the wheel only with Alt held, and its detector cannot be given a different one, so
 * this takes the scroll that shortcut leaves alone. Consumed scrolls are skipped, so Alt + wheel is never zoomed twice.
 * Ctrl + wheel is not a Telephoto shortcut and zooms here like a plain wheel.
 */
internal fun Modifier.wheelZooms(zoom: ZoomableState): Modifier = pointerInput(zoom) {
    val queue = WheelZoomQueue()
    val requests = Channel<Unit>(Channel.CONFLATED)
    coroutineScope {
        launch {
            applyWheelZooms(queue, requests, { zoom.userZoom() }) { step -> zoom.zoomBy(step.factor, step.focalPoint) }
        }
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.type != PointerEventType.Scroll || event.changes.any { it.isConsumed }) continue
                val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                val focalPoint = event.changes.fold(Offset.Zero) { sum, change -> sum + change.position } / event.changes.size.toFloat()
                queue.request(zoom.userZoom(), delta, focalPoint)
                requests.trySend(Unit)
            }
        }
    }
}

/** The picture's current user zoom, or 1 before Telephoto has measured it. */
private fun ZoomableState.userZoom(): Float = contentTransformation.takeIf { it.isSpecified }?.scaleMetadata?.userZoom ?: MIN_USER_ZOOM

/** Pans a zoomed picture by [delta]; always consumes the key, so the arrow does not also turn the page. */
private fun pan(scope: CoroutineScope, zoom: ZoomableState?, delta: Offset): Boolean {
    scope.launch { zoom?.panBy(delta) }
    return true
}

@Composable
private fun pageDescription(kind: ImageKind, position: Int, total: Int): String = when (kind) {
    ImageKind.Backdrop -> stringResource(Res.string.image_viewer_backdrop, position, total)
    ImageKind.Poster -> stringResource(Res.string.image_viewer_poster, position, total)
    ImageKind.Still -> stringResource(Res.string.image_viewer_still, position, total)
}

/** True for the first page when the source's copy of the tapped [seed] replaces it: the same file at another address. */
internal fun TitleImage.replacesSeed(seed: TitleImage?): Boolean = seed != null && fileKey == seed.fileKey && url != seed.url

private const val MAX_ZOOM = MAX_USER_ZOOM

/** How far an arrow key moves a zoomed picture. */
private val KEY_PAN_STEP = 48.dp
