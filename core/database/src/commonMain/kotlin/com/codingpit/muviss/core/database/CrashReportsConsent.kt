package com.codingpit.muviss.core.database

import app.cash.sqldelight.db.SqlDriver

/**
 * The stored "Send crash reports" preference, readable *before* Koin exists.
 *
 * Crash reporting has to start ahead of the dependency graph — a failure while
 * the graph is being built is exactly what it is for — but the switch lives in
 * the database that graph opens. So this opens the database on its own
 * short-lived driver, reads one column, and closes it again; the graph opens
 * its own later as usual.
 *
 * **Any failure reads as [DEFAULT] (on).** The setting defaults on, and a
 * database that cannot be read is precisely the kind of crash worth seeing. It
 * is a deliberate trade against the opposite reading: an opted-out person whose
 * database is *also* unreadable at that moment would have that one crash sent.
 * `docs/PRIVACY.md` says so plainly.
 *
 * Synchronous, so Android/iOS/JVM only in practice: web's driver is async, and
 * web never reports (its `CrashReporter` is a no-op), so nothing calls this
 * there — and if something did, the failure would read as [DEFAULT] as above.
 */
object CrashReportsConsent {
    const val DEFAULT: Boolean = true

    fun read(factory: DatabaseDriverFactory): Boolean {
        val driver = runCatching { factory.create() }.getOrNull() ?: return DEFAULT
        return try {
            read(driver)
        } finally {
            runCatching { driver.close() }
        }
    }

    internal fun read(driver: SqlDriver): Boolean = runCatching {
        MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOneOrNull()?.crashReportsEnabled
    }.getOrNull() ?: DEFAULT
}
