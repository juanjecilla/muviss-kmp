package com.codingpit.muviss.core.common.locale

import kotlinx.browser.window

/**
 * Shared js+wasmJs actual (`webMain`, the default hierarchy template's
 * intermediate source set for both web targets) — unlike `DataExporter`'s
 * `Blob` split, `Navigator.language` interops identically on `js` and
 * `wasmJs`, so one file covers both.
 */
actual class DefaultSystemLocale actual constructor() : SystemLocale {
    actual override val languageTag: String?
        get() = window.navigator.language
}
