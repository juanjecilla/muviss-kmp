package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop has no OS share sheet: prompts a native "Save As" dialog
 * (`java.awt.FileDialog`) so the user picks where the export file lands —
 * the same "let the user pick a destination" intent as the Android/iOS
 * share sheets. `FileDialog.isVisible = true` blocks the calling thread
 * until the user picks a location or cancels, which is the documented,
 * expected way to drive it (it pumps its own native event loop rather than
 * deadlocking the caller).
 */
private class JvmDataExporter : DataExporter {
    override fun export(json: String, fileName: String) {
        val dialog = FileDialog(null as Frame?, "Export Muviss data", FileDialog.SAVE)
        dialog.file = fileName
        dialog.isVisible = true

        val directory = dialog.directory
        val chosenFileName = dialog.file
        if (directory != null && chosenFileName != null) {
            File(directory, chosenFileName).writeText(json)
        }
    }
}

@Composable
actual fun rememberDataExporter(): DataExporter = remember { JvmDataExporter() }
