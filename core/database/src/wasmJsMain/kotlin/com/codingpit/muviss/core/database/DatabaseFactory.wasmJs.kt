package com.codingpit.muviss.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import kotlinx.browser.window
import org.w3c.dom.MessageEvent
import org.w3c.dom.MessagePort
import org.w3c.dom.Worker

/**
 * Real, **durable** persistence (EPIC 24; see
 * `docs/adr/0008-migration-baseline-and-deferred-web-persistence.md`).
 *
 * Backed by SQLDelight's `web-worker-driver` talking to [sqljsWorker] — our own
 * SQL.js worker (`core/database/src/webWorker`) rather than
 * `@cashapp/sqldelight-sqljs-worker`, which calls `new SQL.Database()`
 * unconditionally, never exports, and offers no option, hook or VFS to change
 * that. The database is restored from an IndexedDB snapshot when the worker
 * starts and re-exported after writes, so a page reload no longer empties the
 * user's library.
 *
 * [SchemaEnsuringDriver] (shared in `webMain`) decides create-vs-migrate from
 * `PRAGMA user_version` on the first query — the job every other platform's
 * driver does for us and web's does not. Before EPIC 24 it could create
 * unconditionally, because web's database was always empty.
 *
 * This actual and its js twin are the same code bar the `JsAny` return type on
 * [flushMessage], and it would be
 * one `webMain` file if it could be: [WebWorkerDriver]'s parameter type is
 * `expect class app.cash.sqldelight.driver.worker.expected.Worker`, whose wasmJs
 * actual is an **internal** typealias to [Worker]. A shared file can name neither
 * resolution — `org.w3c.dom.Worker` fails `compileWebMainKotlinMetadata` (a
 * metadata compilation cannot see through an expect class) and `expected.Worker`
 * fails `compileKotlinWasmJs` (the typealias is internal to the library).
 */
actual class DatabaseDriverFactory {
    // Eager rather than lazy: `persistence` is read by the UI as soon as the app
    // composes, and a worker that has not been spawned yet would leave the
    // banner stuck on Pending until something first touched the database.
    private val status = WebPersistenceStatus()

    private val worker: Worker = sqljsWorker().also {
        flushSnapshotOnPageHide(it)
        observePersistence(it, status)
    }

    actual fun create(): SqlDriver = SchemaEnsuringDriver(WebWorkerDriver(worker))

    actual val persistence: PersistenceStatus = status
}

/**
 * Webpack 5's *native* worker support only recognizes the exact combined
 * expression `new Worker(new URL(specifier, import.meta.url), options)` — both
 * `new`s together, literally — as the signal to treat the referenced file as its
 * own entry point and recursively bundle *its* imports. Our worker does `import
 * initSqlJs from "sql.js"`, which resolves only if webpack processes it.
 * Building the URL and the `Worker` in two Kotlin calls compiles fine and defeats
 * that detection: webpack copies the file verbatim instead, leaving a bare
 * specifier the browser cannot resolve. The symptom is a worker that loads with a
 * 200 and then hangs forever with **no console error at all**, because the
 * failure happens inside the worker's own module loading, invisible to the main
 * thread. Keep both `new`s in one untouched `js(...)` snippet.
 *
 * The specifier resolves through node_modules because `muviss-sqljs-worker` is
 * declared as a **local npm package** in this module's `build.gradle.kts`. That
 * is why the worker is a package rather than a file in resources — a path
 * reference gets the verbatim-copy treatment described above.
 *
 * `{ type: "module" }` is kept because it is part of the shape webpack matches
 * on, but note that it does **not** reach the browser: webpack rewrites it to
 * `{ type: void 0 }` and emits a *classic* worker chunk that pulls its
 * dependencies in with `importScripts` (confirmed by reading the built
 * `webApp.js`). ADR 0008 used to claim the module type was required on its own
 * merits, because the worker source is an ES module a classic worker could not
 * parse; that is wrong, and our worker depends on being classic — see the
 * `typeof importScripts === "function"` guard at the bottom of it.
 *
 * Kotlin/Wasm requires a `js(...)` call to be the sole expression of a top-level
 * function body, hence this is its own function rather than being inlined.
 */
private fun sqljsWorker(): Worker = js(
    """new Worker(new URL("muviss-sqljs-worker/muviss-sqljs.worker.js", import.meta.url), { type: "module" })""",
)

/**
 * Bounds how much a crash can cost. The worker coalesces snapshots on a ~500ms
 * debounce, so an idle tab that is killed loses at most that window — but a tab
 * closed normally can do better, and only the main thread can see it happening:
 * a worker gets no `pagehide` of its own, it is simply terminated.
 *
 * Best-effort by nature. The browser may tear the tab down before IndexedDB
 * commits, which is exactly why the debounce exists rather than relying on this.
 */
private fun flushSnapshotOnPageHide(worker: Worker) {
    window.addEventListener("pagehide", { worker.postMessage(flushMessage()) })
}

private fun flushMessage(): JsAny = js("""({ action: "flush" })""")

/**
 * Subscribes to the worker's persistence announcements (issue #53).
 *
 * Over a `MessageChannel` of our own rather than the worker's own message
 * channel, and that is not fastidiousness. The first version did share it,
 * having read the **js** `web-worker-driver` klib, where `WorkerWrapper` adds a
 * per-request listener that compares `event.data.id` and ignores what it does
 * not recognise. The **wasmJs** driver is a different implementation:
 * `WasmWorkerResponse.results` is a non-null external property and it is
 * materialised before the id is looked at, so an extra message with no
 * `results` crashes the app outright —
 *
 *     NullPointerException: null
 *       at ...WasmWorkerResultWithRowCount.<init>
 *       at ...results_$external_prop_getter__externalAdapter
 *
 * — which is what happened, on the second tab, at the exact moment the banner
 * this all exists for appeared. The two targets sharing `webMain` does not mean
 * they share a driver.
 *
 * The port is created and handed over inside one `js(...)` snippet because the
 * transfer list is the whole point of the call and Kotlin's `postMessage`
 * bindings for it differ between the two targets; `start()` is required before a
 * `MessagePort` delivers anything to a listener.
 */
private fun observePersistence(worker: Worker, status: WebPersistenceStatus) {
    val port = persistencePort(worker)
    port.addEventListener("message", { event ->
        val data = (event as MessageEvent).data
        if (data != null) {
            status.report(writer = isWriter(data), webLocksSupported = isSupported(data))
        }
    })
}

/**
 * `@Suppress("UnusedParameter")` on these three: the parameters are referenced
 * by name *inside* the `js(...)` string, which is how Kotlin's JS interop
 * passes them, and Detekt reads Kotlin rather than the embedded JavaScript. It
 * cannot see the use and reports every one of them as dead.
 */
@Suppress("UnusedParameter")
private fun persistencePort(worker: Worker): MessagePort = js(
    """(function () {
        var channel = new MessageChannel();
        worker.postMessage({ action: "muviss_persistence_port" }, [channel.port2]);
        channel.port1.start();
        return channel.port1;
    })()""",
)

@Suppress("UnusedParameter")
private fun isWriter(data: JsAny): Boolean = js("!!data.writer")

@Suppress("UnusedParameter")
private fun isSupported(data: JsAny): Boolean = js("!!data.supported")
