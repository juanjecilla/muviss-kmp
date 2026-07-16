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
 * Wasm twin of `FileImporter.js.kt` — same throwaway `<input type=file>` +
 * `FileReader` read, but Kotlin/Wasm's `FileReader.result` comes back as
 * `JsString?` rather than a plain `kotlin.String?` (see the JS version's
 * KDoc for why this can't be a shared `webMain` actual — same reason as
 * `DataExporter.wasmJs.kt`'s `Blob` interop split).
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
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
        reader.onload = { onResult((reader.result as? JsString)?.toString()) }
        reader.onerror = { onResult(null) }
        reader.readAsText(file)
    }
}

@Composable
actual fun rememberFileImporter(): FileImporter = remember { WebFileImporter() }
