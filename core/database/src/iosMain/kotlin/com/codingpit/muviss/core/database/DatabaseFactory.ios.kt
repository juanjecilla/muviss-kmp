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
import platform.posix.LOCK_EX
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.flock
import platform.posix.open

/** The App Group the app and its widget extension share. Must match the entitlement on both targets. */
const val MUVISS_APP_GROUP: String = "group.com.codingpit.muviss"

internal const val DATABASE_FILE_NAME = "muviss.db"

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
 * Inside that container (or Documents) it sits in its own `Database`
 * subdirectory, because Device Backup (ADR 0034) flags the database's
 * directory, and flagging the container root would keep everything else the
 * app and widget store there out of the device backup too.
 *
 * Existing installs are moved once, on first open after upgrading — see
 * [relocateDatabase]. If the container is unavailable (an app
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
        val destination = preferredDatabaseDirectory()
        NSFileManager.defaultManager.createDirectoryAtPath(destination, withIntermediateDirectories = true, attributes = null, error = null)
        relocateDatabase(sources = legacyDatabaseDirectories(), destination = destination)
        // Where the database is now: the destination, unless a move failed and was put back.
        val directory = databaseDirectory()
        NativeSqliteDriver(
            schema = MuvissDatabase.Schema.synchronous(),
            name = DATABASE_FILE_NAME,
            onConfiguration = { config ->
                config.copy(extendedConfig = config.extendedConfig.copy(basePath = directory))
            },
        ).also { driver ->
            // A failed create/migrate must not leave this driver's connections
            // open behind the caller's back; the caller sees the failure.
            runCatching { driver.executeQuery(null, "SELECT 1", { QueryResult.Value(Unit) }, 0) }
                .onFailure { runCatching { driver.close() } }
                .getOrThrow()
            // ADR 0034: the OS exclusion is re-established on every open, from the stored choice. The
            // directory's flag covers -wal/-shm files SQLite recreates while the app runs.
            DeviceBackupExclusion.apply(storedDeviceBackupEnabled(driver))
        }
    }

    /** A file this app owns; there is nothing to warn anyone about. */
    actual val persistence: PersistenceStatus = AlwaysDurable
}

/**
 * Moves an older install's database into [destination], once: the first of
 * [sources] that holds `muviss.db` wins. Returns whether [destination] now
 * holds the database (or there was nothing to move).
 *
 * A copy would be worse than a move: two files would exist, the app would
 * read the new one and any code still resolving the old path would read a
 * library frozen at upgrade time. Nothing moves once the destination holds a
 * database — a half-finished move must never overwrite the database the app
 * has been writing to since.
 *
 * SQLite's `-wal` and `-shm` sidecars move with it, from the same source, and
 * **before** it. Leaving them behind would strand the most recent
 * transactions in a WAL file the relocated database no longer sees, which
 * reads to a person as "the last few episodes I ticked are gone". Moving them
 * first makes an interrupted run recoverable: the main file is still at the
 * source, so the next open finds it there, moves it, and finds its sidecars
 * already waiting. A move that fails is undone, so the files stay together
 * at the source and [databaseDirectory] keeps opening them there.
 */
internal fun relocateDatabase(
    sources: List<String>,
    destination: String,
    move: (from: String, to: String) -> Boolean = { from, to -> NSFileManager.defaultManager.moveItemAtPath(from, toPath = to, error = null) },
): Boolean {
    val manager = NSFileManager.defaultManager
    if (manager.fileExistsAtPath("$destination/$DATABASE_FILE_NAME")) return true
    val source = sources.firstOrNull { it != destination && manager.fileExistsAtPath("$it/$DATABASE_FILE_NAME") } ?: return true
    for (suffix in listOf("-wal", "-shm", "")) {
        val from = "$source/$DATABASE_FILE_NAME$suffix"
        val to = "$destination/$DATABASE_FILE_NAME$suffix"
        if (!manager.fileExistsAtPath(from)) continue
        if (manager.fileExistsAtPath(to) || !move(from, to)) {
            putSidecarsBack(source, destination, move)
            return false
        }
    }
    return true
}

/**
 * Undoes a move that did not finish. The destination holds no `muviss.db` here, so any sidecar in it belongs to
 * [source]'s database, whether this attempt moved it or an earlier, interrupted one did. Each goes back unless the
 * source already has its own. If one cannot go back, the database would open without its log, which can lose
 * committed transactions, so this throws and the open fails instead.
 */
private fun putSidecarsBack(source: String, destination: String, move: (String, String) -> Boolean) {
    val manager = NSFileManager.defaultManager
    for (suffix in listOf("-wal", "-shm")) {
        val moved = "$destination/$DATABASE_FILE_NAME$suffix"
        val home = "$source/$DATABASE_FILE_NAME$suffix"
        if (!manager.fileExistsAtPath(moved) || manager.fileExistsAtPath(home)) continue
        check(move(moved, home)) { "could not return $DATABASE_FILE_NAME$suffix to the database it belongs to" }
    }
}

private val createLock = NSLock()

/**
 * Serializes [DatabaseDriverFactory.create] within this process ([createLock], #251) **and** across processes: the
 * widget extension opens the same App Group database, so the app and the widget could otherwise both run
 * [relocateDatabase] at once and split `muviss.db` from its `-wal`. The cross-process half is an advisory `flock`
 * on a file next to the database directory, held through the move and the first open. Without the App Group each
 * process has its own Documents and nothing to share, and the lock is still harmless. If the lock file cannot be
 * opened, the in-process lock alone applies, as before.
 */
private inline fun <T> withCreateLock(block: () -> T): T {
    createLock.lock()
    val fd = open("${sharedContainerPath() ?: documentsPath()}/$CREATE_LOCK_FILE_NAME", O_CREAT or O_RDWR, S_IRUSR or S_IWUSR)
    try {
        if (fd >= 0) flock(fd, LOCK_EX)
        return block()
    } finally {
        if (fd >= 0) {
            flock(fd, LOCK_UN)
            close(fd)
        }
        createLock.unlock()
    }
}

private const val CREATE_LOCK_FILE_NAME = ".muviss-db.lock"

/**
 * The directory the database lives in: a `Database` subdirectory of the App Group container, or of Documents
 * without the entitlement (ADR 0016). Its own directory, so the backup exclusion on it covers nothing else (ADR 0034).
 * While an older install's database has not been moved there (a failed [relocateDatabase]), it is wherever that
 * database still is, so the app and the exclusion keep pointing at the real library.
 */
internal fun databaseDirectory(): String = resolveDatabaseDirectory(preferredDatabaseDirectory(), legacyDatabaseDirectories())

internal fun resolveDatabaseDirectory(destination: String, sources: List<String>): String {
    val manager = NSFileManager.defaultManager
    if (manager.fileExistsAtPath("$destination/$DATABASE_FILE_NAME")) return destination
    return sources.firstOrNull { it != destination && manager.fileExistsAtPath("$it/$DATABASE_FILE_NAME") } ?: destination
}

internal fun preferredDatabaseDirectory(): String = databaseDirectoryIn(sharedContainerPath() ?: documentsPath())

/**
 * Where earlier builds kept the database, newest first: the App Group container's root (before ADR 0034),
 * Documents' `Database` directory (a build without the entitlement, since ADR 0034), and Documents itself
 * (before ADR 0016, or a build without the entitlement before ADR 0034).
 */
private fun legacyDatabaseDirectories(): List<String> {
    val documents = documentsPath()
    return listOfNotNull(sharedContainerPath(), databaseDirectoryIn(documents), documents).distinct()
}

internal fun databaseDirectoryIn(base: String): String = "$base/$DATABASE_DIRECTORY_NAME"

private const val DATABASE_DIRECTORY_NAME = "Database"

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
