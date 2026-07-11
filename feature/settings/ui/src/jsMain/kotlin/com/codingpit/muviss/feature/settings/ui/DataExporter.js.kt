package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

/**
 * Browser download via a throwaway `<a download>` anchor + object URL: the
 * standard script-driven "save this as a file" pattern, no extra permission
 * prompt (unlike the File System Access API). See `DataExporter.wasmJs.kt`
 * for the Wasm twin — the two targets' `org.w3c.files.Blob` interop types
 * differ enough (`Array<Any?>` vs `JsArray<JsAny?>`) that this can't live in
 * a shared `webMain` source set.
 *
 * Works independently of `:core:database`; see that KDoc for why the export
 * JSON this receives never actually arrives on today's web build.
 */
private class WebDataExporter : DataExporter {
    override fun export(json: String, fileName: String) {
        val blob = Blob(arrayOf(json), BlobPropertyBag(type = "application/json"))
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
