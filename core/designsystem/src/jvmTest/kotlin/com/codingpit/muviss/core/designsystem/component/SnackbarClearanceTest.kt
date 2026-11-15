package com.codingpit.muviss.core.designsystem.component

import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import kotlin.test.Test
import kotlin.test.assertEquals

class SnackbarClearanceTest {

    @Test
    fun `before anything is measured the list clears a one-line snackbar`() {
        // Given a clearance no snackbar has been measured into
        val clearance = SnackbarClearance()

        // When / Then
        assertEquals(MuvissSpacing.bottomContentWithSnackbar, clearance.bottomPadding)
    }

    @Test
    fun `a snackbar taller than the allowance pushes the list end past it`() {
        // Given a snackbar that wrapped to 200dp
        val clearance = SnackbarClearance()

        // When it is measured
        clearance.onMeasured(200.dp)

        // Then the list ends below the wrapped snackbar
        assertEquals(MuvissSpacing.bottomContent + 200.dp, clearance.bottomPadding)
    }

    @Test
    fun `a snackbar shorter than the allowance keeps the one-line padding`() {
        // Given a snackbar shorter than the allowance
        val clearance = SnackbarClearance()

        // When it is measured
        clearance.onMeasured(40.dp)

        // Then the padding does not shrink below the one-line allowance
        assertEquals(MuvissSpacing.bottomContentWithSnackbar, clearance.bottomPadding)
    }
}
