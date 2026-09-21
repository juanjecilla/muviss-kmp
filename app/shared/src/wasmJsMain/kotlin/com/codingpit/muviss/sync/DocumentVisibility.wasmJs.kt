package com.codingpit.muviss.sync

@JsFun("() => document.visibilityState === 'visible'")
private external fun documentIsVisible(): Boolean

internal actual fun isDocumentVisible(): Boolean = documentIsVisible()
