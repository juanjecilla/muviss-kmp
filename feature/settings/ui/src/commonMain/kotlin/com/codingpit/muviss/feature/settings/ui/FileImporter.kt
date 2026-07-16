package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable

/** One file the user picked, already read into memory — small personal export files, no need to stream. */
data class PickedFile(
    val fileName: String,
    val content: String,
)

/**
 * Platform hand-off for picking an import file — the mirror of [DataExporter]
 * (EPIC 8): Android/desktop/web present a real system file picker; iOS is
 * best-effort (see `FileImporter.ios.kt`). [pickFile] suspends until the user
 * picks a file or dismisses the picker (null).
 */
interface FileImporter {
    suspend fun pickFile(): PickedFile?
}

/**
 * Builds the platform [FileImporter]. Android needs a `Context`/activity
 * result launcher read from the composition (same reason `rememberDataExporter`
 * does); every other target constructs one with no arguments.
 */
@Composable
expect fun rememberFileImporter(): FileImporter
