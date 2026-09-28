package com.codingpit.muviss.core.common.locale

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/**
 * `NSLocale.preferredLanguages` (not `NSLocale.currentLocale`) is the user's
 * ranked language preference list, e.g. `["es-ES", "en-US"]` — the first
 * entry is what `NSLocale.currentLocale` derives its *format* conventions
 * from too, but `preferredLanguages` is the one documented as tracking the
 * Settings app's Language list live.
 */
actual class DefaultSystemLocale actual constructor() : SystemLocale {
    actual override val languageTag: String?
        get() = NSLocale.preferredLanguages.firstOrNull() as? String
}
