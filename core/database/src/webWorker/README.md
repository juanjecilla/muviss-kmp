# muviss-sqljs-worker

The web database worker: our fork of `@cashapp/sqldelight-sqljs-worker`, which
runs SQL.js in a Web Worker and restores/saves the database through IndexedDB.
It is a local npm package on purpose, and it is bundled into the web app by the
Kotlin/JS build, not published anywhere.

## It is intentionally lockless

There is **no `package-lock.json` in this directory, and that is a decision**
(issue #589). `npm ci` here fails by design:

```
npm error code EUSAGE
npm error The `npm ci` command can only install with an existing package-lock.json
```

Do not generate and commit a lockfile here. Nothing installs this directory with
npm, so a lockfile would be a second source of truth that nothing reads.

The lock for this package lives in the Kotlin/JS install, not here:

- `core/database/build.gradle.kts` declares it as
  `npm("muviss-sqljs-worker", File(rootDir, "core/database/src/webWorker"))`.
- Kotlin's npm install records it in `kotlin-js-store/yarn.lock` (wasm: `kotlin-js-store/wasm/yarn.lock`)
  as `"muviss-sqljs-worker@file:../../core/database/src/webWorker"`, with its
  `sql.js` resolved from `package.json`.
- Dependabot's npm entry for this directory (`.github/dependabot.yml`) bumps
  `package.json`, and the `kotlin-js-store` locks move with it in the same PR
  (for example #537). That is the update path; there is no lockfile to bump.

## Running its Node test

```sh
node --test core/database/src/webWorker/test/snapshot-write.test.mjs
```

It needs Node only and no `npm install`: the test loads the worker into a fresh
VM realm with a faked IndexedDB, and stubs the `sql.js` import, since nothing in
the test executes SQL. CI runs the same command (`.github/workflows/ci.yml`,
"Web worker tests").

## Why it must stay a local npm package

Do not move `muviss-sqljs.worker.js` into resources, and do not reference it by
path. The worker's `import initSqlJs from "sql.js"` only gets bundled when webpack
reaches it through `new URL("muviss-sqljs-worker/...", import.meta.url)`, which
resolves through `node_modules`. A path reference is copied verbatim, the bare
`sql.js` import survives, and the worker loads (200) and then hangs on every query
with no console error. See `docs/adr/0008-migration-baseline-and-deferred-web-persistence.md`
(the 2026-09-04 amendment, and snag 1 in the 2026-07-16 amendment) and the "Web + DB"
note in `CLAUDE.md`.
