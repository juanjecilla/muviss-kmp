package com.codingpit.muviss.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import org.w3c.dom.Worker

/**
 * Real persistence (EPIC 13; see `docs/adr/0008-migration-baseline-and-deferred-web-persistence.md`'s
 * second amendment): backed by SQLDelight's `web-worker-driver`, which talks
 * to a Web Worker running `@cashapp/sqldelight-sqljs-worker` (SQL.js —
 * SQLite compiled to wasm) over `postMessage`. That is why `generateAsync`
 * is on for the whole schema now (`core/database`'s `build.gradle.kts`) —
 * every mutating query becomes a real `suspend fun` on every platform, not
 * just web. The schema round-trip itself is deferred by
 * [SchemaEnsuringDriver], which is shared with wasm in `webMain`.
 *
 * SQL.js keeps the database **in memory inside the worker for the tab's
 * lifetime only** — there is no persistent backing store (no OPFS, no
 * IndexedDB snapshot) wired up. A full page reload starts from an empty
 * database. That is a deliberate v1 scope cut, not an oversight — see the
 * ADR.
 *
 * This actual and its wasm twin (`DatabaseFactory.wasmJs.kt`) are byte-for-byte
 * the same code, and it would be one `webMain` file if it could be: it cannot,
 * because [WebWorkerDriver]'s parameter type is `expect class
 * app.cash.sqldelight.driver.worker.expected.Worker`, whose wasmJs actual is an
 * **internal** `typealias` to [Worker]. A shared `webMain` file therefore
 * cannot name the parameter type at all — spelling it `org.w3c.dom.Worker`
 * fails `compileWebMainKotlinMetadata` (`actual type is 'org.w3c.dom.Worker',
 * but 'app.cash.sqldelight.driver.worker.expected.Worker' was expected`, since
 * a metadata compilation cannot see through an expect class), and spelling it
 * `expected.Worker` fails `compileKotlinWasmJs` (`Cannot access 'typealias
 * Worker = Worker': it is internal in file`). Per target, where the typealias
 * has already been resolved, [Worker] is simply the right type.
 *
 * The library's own `createDefaultWebWorkerDriver()` is not a way out: its js
 * actual builds the URL and the `Worker` in two separate calls, which is
 * exactly the shape that defeats webpack's worker bundling (see [sqljsWorker]),
 * and neither actual passes `{ type: "module" }`.
 */
actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver = SchemaEnsuringDriver(WebWorkerDriver(sqljsWorker()))
}

/**
 * Webpack 5's *native* worker support only recognizes the exact combined
 * expression `new Worker(new URL(specifier, import.meta.url), options)` —
 * both calls together, literally — as the signal to treat the referenced
 * file as its own entry point and recursively bundle *its* imports too
 * (the worker source, `@cashapp/sqldelight-sqljs-worker`, does `import
 * initSqlJs from "sql.js"`, which only resolves if webpack processes it).
 * Building the URL and the `Worker` in two separate Kotlin calls compiles
 * fine but breaks that detection — webpack falls back to its generic
 * `new URL(...)` handling, which just copies the referenced file byte for
 * byte *without* bundling its own imports, leaving a dangling bare `import
 * "sql.js"` that the browser cannot resolve at runtime (confirmed via a
 * live browser check: the worker hung forever with no console error, since
 * the failure happens inside the worker's own module-loading step, not on
 * the main thread). Keeping both `new`s in one untouched `js(...)` snippet
 * preserves the exact shape webpack's `WorkerPlugin` pattern-matches on.
 *
 * `{ type: "module" }` is required alongside this because the worker
 * source is an ES module (top-level `import`) — a classic (non-module)
 * Worker cannot parse an `import` statement at all.
 *
 * Kotlin/Wasm requires a `js(...)` call to be the sole expression of a
 * top-level function body or property initializer (it cannot be a
 * statement inside a larger function), hence this is its own function
 * rather than being inlined into [DatabaseDriverFactory.create]. The
 * declared return type is the external [Worker] class rather than
 * `JsAny`/`dynamic`, which Kotlin/Wasm's JS interop allows for `js(...)`.
 */
private fun sqljsWorker(): Worker = js(
    """new Worker(new URL("@cashapp/sqldelight-sqljs-worker/sqljs.worker.js", import.meta.url), { type: "module" })""",
)
