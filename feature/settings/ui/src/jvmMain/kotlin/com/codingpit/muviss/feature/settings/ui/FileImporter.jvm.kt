package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Desktop twin of `DataExporter.jvm.kt`'s `FileDialog` usage, in `LOAD` mode. */
private class JvmFileImporter : FileImporter {
    override suspend fun pickFile(): PickedFile? {
        val dialog = FileDialog(null as Frame?, "Import Muviss data", FileDialog.LOAD)
        dialog.isVisible = true

        val directory = dialog.directory ?: return null
        val fileName = dialog.file ?: return null
        val file = File(directory, fileName)
        return runCatching { PickedFile(file.name, file.readText()) }.getOrNull()
    }
}

@Composable
actual fun rememberFileImporter(): FileImporter = remember { JvmFileImporter() }
