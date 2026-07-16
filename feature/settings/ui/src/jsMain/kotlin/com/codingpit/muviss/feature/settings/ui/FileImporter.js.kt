package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume

/**
 * A throwaway `<input type=file>` triggered programmatically (the standard
 * script-driven "open a file picker" pattern, mirroring `DataExporter.js.kt`'s
 * throwaway `<a download>` for saving) — reads the picked file as text via
 * `FileReader`. See `FileImporter.wasmJs.kt` for the Wasm twin: `FileReader`'s
 * `result` is a plain `String?` here but a `JsString?` there.
 */
private class WebFileImporter : FileImporter {
    override suspend fun pickFile(): PickedFile? = suspendCancellableCoroutine { continuation ->
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = ".json,.csv,application/json,text/csv"
        input.onchange = {
            val file: File? = input.files?.item(0)
            if (file == null) {
                continuation.resume(null)
            } else {
                readAsText(file, onResult = { text -> continuation.resume(text?.let { PickedFile(file.name, it) }) })
            }
        }
        input.click()
    }

    private fun readAsText(file: File, onResult: (String?) -> Unit) {
        val reader = FileReader()
        reader.onload = { onResult(reader.result as? String) }
        reader.onerror = { onResult(null) }
        reader.readAsText(file)
    }
}

@Composable
actual fun rememberFileImporter(): FileImporter = remember { WebFileImporter() }
