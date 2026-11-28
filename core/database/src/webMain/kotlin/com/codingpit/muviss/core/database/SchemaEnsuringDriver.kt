package com.codingpit.muviss.core.database

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.async.coroutines.awaitMigrate
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The shared half of web persistence, in the `webMain` intermediate source set
 * so `js` and `wasmJs` compile one copy of it (see `DatabaseFactory.js.kt` for
 * why the `DatabaseDriverFactory` actual itself cannot live up here).
 *
 * `WebWorkerDriver`'s constructor is not suspend — spawning a `Worker` is
 * fire-and-forget, the browser loads and initializes it in the background.
 * What *is* asynchronous is the first round-trip that brings the schema up to
 * date against it. This wrapper defers that round-trip (via [ensureSchema],
 * guarded by [mutex] so concurrent first callers only pay it once) to the first
 * real query instead of doing it in `DatabaseDriverFactory.create` — keeping
 * that function synchronous, exactly like every other platform, so Koin's
 * `single { }` graph never needs to become suspend-aware.
 *
 * **Since EPIC 24 this has to decide, not just create.** It used to call
 * `Schema.awaitCreate` unconditionally, which was correct only because web's
 * database was thrown away on every page load and so was always empty. Now that
 * the worker restores an IndexedDB snapshot, an existing database can arrive
 * with tables already in it and a schema older than this build — the ordinary
 * upgrade path that Android, iOS and JVM get for free from their drivers, and
 * that web has never had because it never had a stored database.
 *
 * Getting this wrong is not a crash. `CREATE TABLE` against a populated
 * database fails, every screen's `Flow` has a `.catch { }`, and the user sees a
 * generic error state on a library that is actually still on disk.
 *
 * Listener bookkeeping (`addListener`/`removeListener`/`notifyListeners`, used
 * by `Query.asFlow()` for reactive reads) is pure in-memory pub/sub on the
 * driver itself, not a query — it needs no such guard, so it (and
 * `newTransaction`/`currentTransaction`/`close`) delegate straight through.
 */
internal class SchemaEnsuringDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    private val mutex = Mutex()
    private var schemaReady = false

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = QueryResult.AsyncValue {
        ensureSchema()
        delegate.executeQuery(identifier, sql, mapper, parameters, binders).await()
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = QueryResult.AsyncValue {
        ensureSchema()
        delegate.execute(identifier, sql, parameters, binders).await()
    }

    /**
     * A caller's `transaction { }` begins here, so the schema must be settled
     * before its `BEGIN` reaches the worker. Without this, a transaction opened
     * before the first query would run its body against a database that has not
     * been created or migrated yet, and [ensureSchema] would then nest its own
     * transaction inside the caller's.
     */
    override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.AsyncValue {
        ensureSchema()
        delegate.newTransaction().await()
    }

    private suspend fun ensureSchema() {
        if (schemaReady) return
        mutex.withLock {
            if (schemaReady) return
            // NonCancellable: once `BEGIN` is sent, the transaction must reach its
            // COMMIT or ROLLBACK. SQLDelight's `transaction { }` does not roll back
            // when the caller is cancelled while awaiting a worker reply, so a
            // cancelled first query would leave the worker's transaction open
            // forever: every later write nests under it, and the snapshot export
            // that waits for no open transaction never runs again (#1027 review).
            withContext(NonCancellable) {
                when (val step = schemaStepFor(readUserVersion(), MuvissDatabase.Schema.version)) {
                    // One transaction, as on Android (the framework's SQLiteOpenHelper) and iOS (SQLiter's
                    // migrateIfNeeded): the schema change and its user_version stamp commit together or not at
                    // all (#1027). Without it each statement autocommits, and the worker's debounced snapshot or
                    // the pagehide flush can store a half-applied migration under the old version, so the next
                    // launch re-runs it over its own partial output (30.sqm's rebuild fails on the table it left).
                    SchemaStep.Create -> MuvissDatabase(delegate).transaction {
                        MuvissDatabase.Schema.awaitCreate(delegate)
                        writeUserVersion(MuvissDatabase.Schema.version)
                    }

                    is SchemaStep.Migrate -> MuvissDatabase(delegate).transaction {
                        MuvissDatabase.Schema.awaitMigrate(delegate, step.from, step.to)
                        writeUserVersion(step.to)
                    }

                    SchemaStep.UpToDate -> Unit
                }
                schemaReady = true
            }
        }
    }

    /**
     * SQLite's own header field, which is where every other platform's driver
     * keeps this too — so a snapshot is self-describing and we do not need a
     * second copy of the version alongside it in IndexedDB that could disagree
     * with the bytes it labels.
     *
     * A freshly created database reports 0, which is what makes [SchemaStep]'s
     * `0 -> Create` branch the same test as "is this database empty".
     */
    private suspend fun readUserVersion(): Long = delegate.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version;",
        mapper = { cursor ->
            QueryResult.AsyncValue {
                if (cursor.next().await()) cursor.getLong(0) ?: 0L else 0L
            }
        },
        parameters = 0,
    ).await()

    private suspend fun writeUserVersion(version: Long) {
        // PRAGMA takes no bind parameters, hence the interpolation. The value is
        // never user input — it comes from the generated schema.
        delegate.execute(null, "PRAGMA user_version = $version;", 0).await()
    }
}

/** What [SchemaEnsuringDriver] must do to a database it has just opened. */
internal sealed interface SchemaStep {
    /** Empty database: create the current schema outright. */
    data object Create : SchemaStep

    /** A restored snapshot older than this build. */
    data class Migrate(val from: Long, val to: Long) : SchemaStep

    /** A restored snapshot this build already understands. */
    data object UpToDate : SchemaStep
}

/**
 * Pulled out as a pure function purely so it can be tested: everything around it
 * needs a live Web Worker, and `jvmTest` — where this repo's Compose and most
 * other tests run — has no browser to give it.
 *
 * A database newer than the build (`current > target`) is treated as up to date
 * rather than as an error. It happens whenever someone opens a deployed older
 * build after a newer one, and SQLDelight has no downgrade path to offer; the
 * queries this build runs are a subset of what a later schema has, so leaving it
 * alone is the behaviour that keeps the user's data intact. Recreating it would
 * mean deleting a library to fix a version number.
 */
internal fun schemaStepFor(current: Long, target: Long): SchemaStep = when {
    current <= 0L -> SchemaStep.Create
    current < target -> SchemaStep.Migrate(from = current, to = target)
    else -> SchemaStep.UpToDate
}
