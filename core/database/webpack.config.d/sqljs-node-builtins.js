// The `resolve.fallback` half of `app/webApp/webpack.config.d/copy-sqljs-wasm.js`,
// repeated here because webpack config in `webpack.config.d` is per module and
// this module now has a browser bundle of its own.
//
// `:core:database`'s `jsBrowserTest`/`wasmJsBrowserTest` did not exist before
// EPIC 24 — the module had no web test source at all, so both tasks were
// SKIPPED and no bundle was ever built here. `SchemaStepTest` gives it one, and
// that bundle reaches `DatabaseFactory.js.kt`'s `new Worker(new URL(...))`,
// which makes webpack pull our worker in as an entry point along with its
// `import initSqlJs from "sql.js"`.
//
// sql.js's loader is written to run in both Node and the browser and
// unconditionally references `fs`/`path`/`crypto` for its Node path — a path a
// browser build never takes at runtime but webpack 5 still has to *resolve* at
// build time. `false` is webpack's documented way to say the reference is
// genuinely unused here.
//
// The `CopyWebpackPlugin` half is deliberately NOT repeated: it exists so the
// worker's `locateFile: () => '/sql-wasm.wasm'` finds the binary at the server
// root at runtime, and these tests never start the worker — they only exercise
// the pure `schemaStepFor`. Anything here that does start it would need the
// copy too, and would be better written as a test in `:app:webApp`.
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, {
    fs: false,
    path: false,
    crypto: false,
});
