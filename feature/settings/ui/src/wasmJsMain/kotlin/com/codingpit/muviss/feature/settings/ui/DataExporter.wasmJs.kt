package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

/**
 * Wasm twin of `DataExporter.js.kt` — same `<a download>` + object-URL
 * download, but Kotlin/Wasm's `org.w3c.files.Blob` interop takes a
 * `JsArray<JsAny?>` instead of a plain `Array<Any?>`, hence the separate file
 * (see the JS version's KDoc for why this can't be a shared `webMain` actual).
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private class WebDataExporter : DataExporter {
    override fun export(json: String, fileName: String) {
        val parts = JsArray<JsString>()
        parts[0] = json.toJsString()

        @Suppress("UNCHECKED_CAST")
        val blob = Blob(parts as JsArray<JsAny?>, BlobPropertyBag(type = "application/json"))
        val url = URL.createObjectURL(blob)
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = url
        anchor.download = fileName
        document.body?.appendChild(anchor)
        anchor.click()
        anchor.remove()
        URL.revokeObjectURL(url)
    }
}

@Composable
actual fun rememberDataExporter(): DataExporter = remember { WebDataExporter() }
