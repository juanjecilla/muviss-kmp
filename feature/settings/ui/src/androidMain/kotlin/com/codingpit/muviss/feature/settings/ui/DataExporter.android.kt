package com.codingpit.muviss.feature.settings.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Hands the JSON to the system share sheet via `ACTION_SEND` (plain text,
 * `application/json`) rather than a `FileProvider` URI — the export is
 * small personal data, not large enough to need file-backed sharing, and
 * this needs no `FileProvider` manifest entry.
 */
private class AndroidDataExporter(private val context: Context) : DataExporter {
    override fun export(json: String, fileName: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_TEXT, json)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Export Muviss data").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
actual fun rememberDataExporter(): DataExporter {
    val context = LocalContext.current
    return remember(context) { AndroidDataExporter(context) }
}
