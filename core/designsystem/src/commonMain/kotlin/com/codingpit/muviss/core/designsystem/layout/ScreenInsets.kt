package com.codingpit.muviss.core.designsystem.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The top band that artwork drawn under the status bar must grow by, and that controls over it must clear.
 *
 * `safeDrawing` rather than `statusBars`: a caption bar on a freeform Android window occupies the top band without
 * a status-bar inset, and `statusBars` does not cover it. Display cutouts are included for the same reason as
 * [ScreenInsets].
 */
@Composable
fun immersiveTopInsets(): WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)

/**
 * The sideways band a landscape display cutout takes, which [extendIntoSideInsets] lets a black or artwork-led screen
 * run under. Read raw, for the same reason as [immersiveTopPadding]: [ScreenInsets] has already consumed it for every
 * descendant. Read it as a parameter's default, so tests can pass a synthetic cutout.
 */
@Composable
fun immersiveSideInsets(): WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

/**
 * The raw height of [immersiveTopInsets], read as a number.
 *
 * Read this, not `immersiveTopInsets().windowInsetsPadding`, inside a screen that [ScreenInsets] already wraps: that
 * padding consumes the top for its descendants, so an inset modifier there would apply nothing.
 */
@Composable
fun immersiveTopPadding(): Dp = immersiveTopInsets().asPaddingValues().calculateTopPadding()

/**
 * Holds app content clear of the system's unsafe area.
 *
 * The app runs edge-to-edge (`enableEdgeToEdge()`, and `targetSdk = 36` forces
 * it regardless), so nothing insets content unless something asks. The shared
 * root is `NavigationSuiteScaffold`, whose content lambda hands out **no**
 * `PaddingValues` and which takes no `contentWindowInsets` — so every screen
 * that isn't built on an M3 `Scaffold` drew its first pixel at y=0, under the
 * status bar and camera cutout. This is the one place that fixes that, for
 * every screen and every platform at once.
 *
 * It pads the top on **every** route, all the time. That is deliberate: an
 * immersive screen (Detail, Episode Detail) does not drop the padding for itself
 * when it is the current destination, because the padding belongs to the
 * `NavHost` as a whole and would then vanish from the outgoing screen during its
 * exit animation and reappear on pop, which is a visible jump. Instead it
 * extends itself up into the band with [Modifier.extendIntoTopInset], in every
 * state, so its placement never changes.
 *
 * [safeDrawing] rather than `systemBars`: it includes `displayCutout`, and a
 * cutout is exactly what a modern phone puts over the top of the screen. The
 * bottom is deliberately left out — the navigation bar and rail inset
 * themselves, and padding it here would leave a gap under them.
 *
 * [Modifier.windowInsetsPadding] **consumes** what it applies, so a descendant
 * `Scaffold` sees what is left rather than padding the same band a second time.
 * No explicit `consumeWindowInsets` is needed.
 *
 * [insets] is a parameter rather than a hard-coded read because Skiko — the
 * renderer behind `runComposeUiTest` — reports zero insets, so this would be
 * untestable off-device otherwise. Tests pass a synthetic value; production
 * uses the default.
 */
@Composable
fun ScreenInsets(
    insets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    content: @Composable () -> Unit,
) {
    Box(Modifier.windowInsetsPadding(insets)) {
        content()
    }
}

/**
 * Lets a screen draw up into the top band that [ScreenInsets] padded, by [top], so its artwork runs under the status
 * bar.
 *
 * The child is measured to fill the band, [top] taller than this node's bounds, and placed [top] above this node's
 * origin. This node keeps the height it was given, so whatever sits beside or below it does not move. The child is
 * forced to the full height, so it must not ask for less (use `fillMaxSize`, or a weight, not a fixed `height`). Nothing
 * clips it: navigation-compose's `NavHost` sets no `SizeTransform`, so `AnimatedContent` does not `clipToBounds` during
 * a transition. Unbounded height, or a [top] of zero, places the child as it is.
 */
fun Modifier.extendIntoTopInset(top: Dp): Modifier = layout { measurable, constraints ->
    val extra = if (top > 0.dp && constraints.hasBoundedHeight) top.roundToPx() else 0
    if (extra == 0) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val height = constraints.maxHeight
    val placeable = measurable.measure(
        Constraints(
            minWidth = constraints.minWidth,
            maxWidth = constraints.maxWidth,
            minHeight = height + extra,
            maxHeight = height + extra,
        ),
    )
    layout(placeable.width, height) { placeable.place(0, -extra) }
}

/**
 * Lets a screen draw sideways into the side bands that [ScreenInsets] padded, by [left] and [right], so a black
 * full-bleed screen does not leave the theme colour beside it on a landscape phone with a cutout.
 *
 * The horizontal counterpart of [extendIntoTopInset]: the child is measured to fill the widened band and placed [left]
 * to the left of this node's origin, and this node keeps the width it was given. The child is forced to the full width,
 * so it must not ask for less (use `fillMaxSize` or a weight). A zero extension places the child as it is, and so does an
 * unbounded width: there is no edge to extend to, so the modifier does nothing rather than guess.
 */
fun Modifier.extendIntoSideInsets(left: Dp, right: Dp): Modifier = layout { measurable, constraints ->
    val extraLeft = if (left > 0.dp && constraints.hasBoundedWidth) left.roundToPx() else 0
    val extraRight = if (right > 0.dp && constraints.hasBoundedWidth) right.roundToPx() else 0
    if (extraLeft + extraRight == 0) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth
    val placeable = measurable.measure(
        Constraints(
            minWidth = width + extraLeft + extraRight,
            maxWidth = width + extraLeft + extraRight,
            minHeight = constraints.minHeight,
            maxHeight = constraints.maxHeight,
        ),
    )
    layout(width, placeable.height) { placeable.place(-extraLeft, 0) }
}
