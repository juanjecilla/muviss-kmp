package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** No-op on web: `:core:database` has no JS driver yet (see CLAUDE.md), so there is nothing to export here today. */
private class NoOpDataExporter : DataExporter {
    override fun export(json: String, fileName: String) = Unit
}

@Composable
actual fun rememberDataExporter(): DataExporter = remember { NoOpDataExporter() }
