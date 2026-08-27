package com.codingpit.muviss.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import org.w3c.dom.Worker

/**
 * Wasm twin of `DatabaseFactory.js.kt` — identical code, separate file. See
 * that file's KDoc for what this does, and for why the pair cannot be one
 * shared `webMain` actual (short version: [WebWorkerDriver] takes an `expect
 * class Worker` whose wasmJs actual is an internal typealias, so no shared
 * source set can name the parameter type).
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver = SchemaEnsuringDriver(WebWorkerDriver(sqljsWorker()))
}

/** See `DatabaseFactory.js.kt`'s `sqljsWorker` for why this shape is load-bearing. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun sqljsWorker(): Worker = js(
    """new Worker(new URL("@cashapp/sqldelight-sqljs-worker/sqljs.worker.js", import.meta.url), { type: "module" })""",
)
