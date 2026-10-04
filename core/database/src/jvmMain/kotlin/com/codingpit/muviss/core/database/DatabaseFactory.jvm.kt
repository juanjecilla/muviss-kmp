package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

private const val DATABASE_FILE_NAME = "muviss.db"

/**
 * File-backed driver for desktop: the database lives under an
 * OS-appropriate app-data directory (see [appDataDirectory]) so the library,
 * progress, settings and profile persist across restarts. Schema is created
 * on first run; later runs migrate forward from the version stamped in the
 * file's `PRAGMA user_version` (see `docs/adr/0008-migration-baseline-and-deferred-web-persistence.md`).
 *
 * [directory] is defaulted rather than fixed so a test can point the whole
 * Koin graph at a temp directory — `:app:shared`'s `AppGraphTest` does, to
 * assemble the real app graph without writing to the developer's actual
 * app-data directory. Production callers construct it with no arguments, and
 * the `expect` declaration is satisfied by the default.
 *
 * jvmTest suites covering *repositories* deliberately bypass this: they
 * construct `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)` directly and call
 * `MuvissDatabase.Schema.create(driver)` themselves, so they stay hermetic
 * and never touch the real file system. `DatabaseFactoryJvmTest` covers this
 * factory's own create/migrate-on-open logic against a temp directory via
 * [createFileDriver], since driving [create] itself would touch the real
 * per-user app-data directory.
 */
actual class DatabaseDriverFactory(private val directory: File = appDataDirectory()) {
    actual fun create(): SqlDriver = createFileDriver(directory)

    /** A file this app owns; there is nothing to warn anyone about. */
    actual val persistence: PersistenceStatus = AlwaysDurable
}

/**
 * The actual create-or-open-and-migrate logic, factored out of [create] so
 * tests can point it at a throwaway directory instead of the real
 * [appDataDirectory].
 */
internal fun createFileDriver(directory: File): SqlDriver = synchronized(createLock) {
    val databaseFile = File(directory, DATABASE_FILE_NAME)
    databaseFile.parentFile?.mkdirs()
    val isNewDatabase = !databaseFile.exists()

    val driver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
    // `.synchronous()` bridges `MuvissDatabase.Schema` (now
    // `SqlSchema<QueryResult.AsyncValue<Unit>>`, see `core/database`'s
    // `build.gradle.kts` on `generateAsync`) back to the synchronous shape
    // this factory has always used — see `DatabaseFactory.android.kt`'s KDoc.
    val schema = MuvissDatabase.Schema.synchronous()
    val targetVersion = schema.version
    if (isNewDatabase) {
        schema.create(driver)
        driver.setUserVersion(targetVersion)
    } else {
        val currentVersion = driver.userVersion()
        if (currentVersion < targetVersion) {
            schema.migrate(driver, currentVersion, targetVersion)
            driver.setUserVersion(targetVersion)
        }
    }
    driver
}

/**
 * Serializes [createFileDriver]'s check-then-create. Desktop opens the
 * database twice at startup on two threads — crash reporting's consent read
 * on a background dispatcher (#124) and the Koin graph on `main` — and the
 * file-exists check, `Schema.create` and the `user_version` stamp are three
 * steps: two first opens both saw no file, both created, and the loser died
 * with "table appSettings already exists". Process-wide, not per directory,
 * because opens are two per launch and never contended otherwise.
 */
private val createLock = Any()

// `Schema.create`/`Schema.migrate` only run the .sq/.sqm statements — unlike
// AndroidSqliteDriver/NativeSqliteDriver, plain JdbcSqliteDriver does no
// version bookkeeping of its own, so callers must stamp `PRAGMA user_version`
// themselves. Skipping this would make every launch re-run `migrate(0, 1)`
// against an already-current database — harmless while there are no `.sqm`
// files yet, but silently wrong (e.g. a repeated `ALTER TABLE ADD COLUMN`
// would fail outright) the moment the first real migration ships.
private fun JdbcSqliteDriver.userVersion(): Long = executeQuery(
    identifier = null,
    sql = "PRAGMA user_version;",
    mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
    parameters = 0,
    binders = null,
).value

private fun JdbcSqliteDriver.setUserVersion(version: Long) {
    execute(identifier = null, sql = "PRAGMA user_version = $version;", parameters = 0, binders = null)
}

/**
 * The OS-appropriate per-user application-data directory for Muviss, created
 * on demand. Mirrors the conventions each desktop OS expects:
 * - macOS: `~/Library/Application Support/Muviss`
 * - Windows: `%APPDATA%\Muviss` (falls back to the user home if unset)
 * - Linux/other: `$XDG_DATA_HOME/Muviss`, or `~/.local/share/Muviss`
 */
internal fun appDataDirectory(): File {
    val userHome = System.getProperty("user.home")
    val osName = System.getProperty("os.name")?.lowercase().orEmpty()
    val baseDir = when {
        osName.contains("mac") || osName.contains("darwin") ->
            File(userHome, "Library/Application Support")

        osName.contains("win") ->
            File(System.getenv("APPDATA") ?: userHome)

        else ->
            File(System.getenv("XDG_DATA_HOME") ?: "$userHome/.local/share")
    }
    return File(baseDir, "Muviss")
}
