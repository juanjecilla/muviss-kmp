package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable

/**
 * Platform hand-off for the exported JSON. Android opens the system share
 * sheet; other targets fall back to a best-effort local file write (or a
 * no-op where even that isn't meaningful yet) — see each `DataExporter.<platform>.kt`.
 */
interface DataExporter {
    fun export(json: String, fileName: String)
}

/**
 * Builds the platform [DataExporter]. Android needs a `Context` read from
 * the composition (same reason `rememberDatabaseDriverFactory` in
 * `app/shared` does); every other target constructs one with no arguments.
 */
@Composable
expect fun rememberDataExporter(): DataExporter
