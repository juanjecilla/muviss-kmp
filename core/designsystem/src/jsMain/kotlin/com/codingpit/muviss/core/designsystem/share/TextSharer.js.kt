package com.codingpit.muviss.core.designsystem.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * `navigator.share` where the browser has it (mobile browsers, Safari), the
 * async clipboard otherwise. Byte-identical to its js/wasmJs twin: `js(...)`
 * must be a top-level function's whole body on Wasm, which a shared `webMain`
 * file cannot express for both backends (the `SentryJs` precedent).
 */
private class WebTextSharer : TextSharer {
    override val canShare: Boolean = webCanShare()

    override fun copy(text: String) = webCopy(text)

    override fun share(text: String) = if (canShare) webShare(text) else webCopy(text)
}

private fun webCanShare(): Boolean = js("typeof navigator !== 'undefined' && typeof navigator.share === 'function'")

// A dismissed share sheet rejects the promise; that is the user's choice, not an error.
// `text` is read inside the `js(...)` body, which detekt cannot see.
@Suppress("UnusedParameter")
private fun webShare(text: String): Unit = js("navigator.share({ text: text }).catch(function () {})")

@Suppress("UnusedParameter")
private fun webCopy(text: String): Unit = js("navigator.clipboard && navigator.clipboard.writeText(text).catch(function () {})")

@Composable
actual fun rememberTextSharer(): TextSharer = remember { WebTextSharer() }
