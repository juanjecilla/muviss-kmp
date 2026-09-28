package com.codingpit.muviss.core.common.locale

/**
 * The platform's current locale, for seeding a user preference that has
 * never been set explicitly (see `feature/settings/domain`'s
 * `SupportedLocales.resolveLanguage`, the first consumer, and issue #74's
 * app-UI language selector, the second).
 *
 * [languageTag] is read fresh from the platform on every access rather than
 * captured once at construction, so a device locale change takes effect the
 * next time something reads it — no reinstall needed, only whatever restart
 * or recomposition the platform already does on a locale change.
 */
interface SystemLocale {
    /** A BCP-47 language tag, e.g. `"es-ES"`, or null if the platform cannot report one. */
    val languageTag: String?
}

/**
 * [SystemLocale] backed by the platform's real locale API: `Locale.getDefault()`
 * on JVM/Android, `NSLocale.preferredLanguages` on iOS, `navigator.language`
 * on web (js/wasmJs share one actual in `webMain`, unlike `DataExporter`'s
 * `Blob` split — `navigator.language` interops identically on both).
 */
expect class DefaultSystemLocale() : SystemLocale {
    override val languageTag: String?
}
