package com.codingpit.muviss.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing

/**
 * How far a scrolling list pads its bottom so its last row clears a snackbar on the same screen.
 *
 * Shared by the seven screens with an undo or confirmation snackbar (#474, #567, #568, #612, #610).
 *
 * A screen keeps one of these per snackbar host: measure the host with [Modifier.measureSnackbarHeight], then
 * pad the list's bottom with [bottomPadding]. That is at least [MuvissSpacing.bottomContentWithSnackbar], the room
 * a one-line snackbar takes, and grows with a wrapped message, which no constant can know about.
 */
@Stable
class SnackbarClearance {

    private var snackbarHeight by mutableStateOf(0.dp)

    /** The bottom padding that lets the last row scroll clear the snackbar. Reads the measured height, so it recomposes when that changes. */
    val bottomPadding: Dp
        get() = maxOf(MuvissSpacing.bottomContentWithSnackbar, MuvissSpacing.bottomContent + snackbarHeight)

    internal fun onMeasured(height: Dp) {
        snackbarHeight = height
    }
}

/** A [SnackbarClearance] that survives recomposition; one per screen, per snackbar host. */
@Composable
fun rememberSnackbarClearance(): SnackbarClearance = remember { SnackbarClearance() }

/** Records the size of the snackbar this modifier is applied to into [clearance]. Apply it to the `SnackbarHost` itself. */
@Composable
fun Modifier.measureSnackbarHeight(clearance: SnackbarClearance): Modifier {
    val density = LocalDensity.current
    return onSizeChanged { clearance.onMeasured(with(density) { it.height.toDp() }) }
}
