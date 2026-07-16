// EPIC 13 (web productionization) / ADR 0008's amendment: the
// `@cashapp/sqldelight-sqljs-worker` package (SQLDelight's `web-worker-driver`
// backend for real web persistence — see `:core:database`'s
// `DatabaseFactory.web.kt`) requests SQL.js's own compiled-to-wasm SQLite
// binary from an *absolute* path: `locateFile: file => '/sql-wasm.wasm'`
// (see the worker's source in `sql.js`'s npm package). Webpack's
// `new URL(specifier, import.meta.url)` bundling (which is what gets the
// worker script itself included in the build, no config needed for that
// part) does not follow that runtime `locateFile` string — it is invisible
// to static analysis — so `sql-wasm.wasm` has to be copied to the output
// root explicitly, or the worker's first query 404s.
const CopyWebpackPlugin = require('copy-webpack-plugin');

config.plugins.push(
    new CopyWebpackPlugin({
        patterns: [
            {
                from: require.resolve('sql.js/dist/sql-wasm.wasm'),
                to: 'sql-wasm.wasm',
            },
        ],
    }),
);

// sql.js's loader (`sql.js/dist/sql-wasm.js`) is written to run in both
// Node and the browser — it feature-detects and conditionally `require`s
// `fs`/`path`/`crypto` for the Node path, which this browser-only build
// never takes at runtime, but webpack 5 (no longer auto-polyfilling core
// Node modules) fails to *resolve* those `require` calls at build time
// regardless of whether they'd execute. Stubbing them to `false` tells
// webpack to leave any reference unresolved-but-harmless instead of
// erroring, matching webpack 5's documented way to opt out of a polyfill
// for a module that is genuinely unused in this target.
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, {
    fs: false,
    path: false,
    crypto: false,
});
