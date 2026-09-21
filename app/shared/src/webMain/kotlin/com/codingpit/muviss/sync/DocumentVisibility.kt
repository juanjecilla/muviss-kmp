package com.codingpit.muviss.sync

/**
 * Whether the page is visible right now (`document.visibilityState === "visible"`).
 * Kotlin's DOM bindings do not expose it, and the JS and Wasm targets reach the
 * browser differently (`js()` versus `@JsFun`), hence one expect with two
 * one-line actuals.
 */
internal expect fun isDocumentVisible(): Boolean
