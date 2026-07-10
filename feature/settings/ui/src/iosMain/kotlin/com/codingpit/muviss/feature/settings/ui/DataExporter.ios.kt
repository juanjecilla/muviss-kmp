package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** No-op on iOS for now: a `UIActivityViewController` share sheet is future work, not required for EPIC 8. */
private class NoOpDataExporter : DataExporter {
    override fun export(json: String, fileName: String) = Unit
}

@Composable
actual fun rememberDataExporter(): DataExporter = remember { NoOpDataExporter() }
