@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/** The App Group the app and its widget extension share. Must match the entitlement on both targets. */
const val MUVISS_APP_GROUP: String = "group.com.codingpit.muviss"

private const val DATABASE_FILE_NAME = "muviss.db"

/**
 * See `DatabaseFactory.android.kt`'s KDoc on [synchronous] — same bridge,
 * same reasoning.
 *
 * The database lives in the shared App Group container rather than the app's
 * own Documents directory (ADR 0016). A WidgetKit extension is a separate
 * process with its own sandbox: `NSDocumentDirectory` resolves to the
 * *extension's* Documents, which is empty, so a widget reading from there
 * would show an empty library forever. The App Group container is the only
 * path both processes resolve to the same file.
 *
 * Existing installs are moved once, on first open after upgrading — see
 * [migrateOutOfDocumentsIfNeeded]. If the container is unavailable (an app
 * built without the entitlement, which is every build until the manual
 * provisioning steps in docs/RELEASING.md are done) this falls back to the
 * old Documents path: no widget, but a working app, rather than a crash on
 * launch.
 */
actual class DatabaseDriverFactory {
    /**
     * Serialized, and the first connection is opened before returning (#251).
     * Startup opens two drivers at once — `CrashReportsConsent` reads on its
     * own while the graph opens the app's — and `NativeSqliteDriver` creates
     * or migrates the schema lazily, on whichever connection is used first.
     * Two of those racing on a fresh or upgraded file meant one got
     * `database is locked` mid-create and the app aborted on 5 of 8 fresh
     * installs. Inside the lock the second driver finds the schema current
     * and does nothing; the JVM factory does the same for the same reason.
     */
    actual fun create(): SqlDriver = withCreateLock {
        val directory = sharedContainerPath() ?: documentsPath()
        migrateOutOfDocumentsIfNeeded(directory)
        NativeSqliteDriver(
            schema = MuvissDatabase.Schema.synchronous(),
            name = DATABASE_FILE_NAME,
            onConfiguration = { config ->
                config.copy(extendedConfig = config.extendedConfig.copy(basePath = directory))
            },
        ).also { driver -> driver.executeQuery(null, "SELECT 1", { QueryResult.Value(Unit) }, 0) }
    }

    /** A file this app owns; there is nothing to warn anyone about. */
    actual val persistence: PersistenceStatus = AlwaysDurable
}

/**
 * Moves a pre-EPIC-22 database out of Documents and into the App Group,
 * once.
 *
 * A copy would be worse than a move: two files would exist, the app would
 * read the new one and any code still resolving the old path would read a
 * library frozen at upgrade time. The move is skipped entirely when the
 * destination already exists — a half-finished move must never overwrite the
 * database the app has been writing to since.
 *
 * SQLite's `-wal` and `-shm` sidecars are moved with it. Leaving them behind
 * would strand the most recent transactions in a WAL file the relocated
 * database no longer sees, which reads to a person as "the last few episodes
 * I ticked are gone".
 */
private fun migrateOutOfDocumentsIfNeeded(destinationDirectory: String) {
    val documents = documentsPath()
    if (destinationDirectory == documents) return

    val manager = NSFileManager.defaultManager
    listOf("", "-wal", "-shm").forEach { suffix ->
        val from = "$documents/$DATABASE_FILE_NAME$suffix"
        val to = "$destinationDirectory/$DATABASE_FILE_NAME$suffix"
        if (manager.fileExistsAtPath(from) && !manager.fileExistsAtPath(to)) {
            manager.moveItemAtPath(from, toPath = to, error = null)
        }
    }
}

private val createLock = NSLock()

private inline fun <T> withCreateLock(block: () -> T): T {
    createLock.lock()
    try {
        return block()
    } finally {
        createLock.unlock()
    }
}

/** The App Group container's path, or null when the entitlement is missing. */
private fun sharedContainerPath(): String? {
    val url: NSURL = NSFileManager.defaultManager
        .containerURLForSecurityApplicationGroupIdentifier(MUVISS_APP_GROUP)
        ?: return null
    return url.path
}

private fun documentsPath(): String = NSSearchPathForDirectoriesInDomains(
    directory = NSDocumentDirectory,
    domainMask = NSUserDomainMask,
    expandTilde = true,
).first() as String
