package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

/** Desktop has no share sheet: writes the export next to the user's home directory instead. */
private class JvmDataExporter : DataExporter {
    override fun export(json: String, fileName: String) {
        File(System.getProperty("user.home"), fileName).writeText(json)
    }
}

@Composable
actual fun rememberDataExporter(): DataExporter = remember { JvmDataExporter() }
