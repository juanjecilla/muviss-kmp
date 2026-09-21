package com.codingpit.muviss.sync

internal actual fun isDocumentVisible(): Boolean = js("document.visibilityState === 'visible'") as Boolean
