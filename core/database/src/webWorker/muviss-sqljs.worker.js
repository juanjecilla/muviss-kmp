// Muviss' own SQL.js Web Worker, replacing @cashapp/sqldelight-sqljs-worker.
//
// WHY A FORK. The vendored worker is sixty lines and has no persistence seam:
// it calls `new SQL.Database()` unconditionally and never exports. There is no
// option, no VFS and no hook to change that, so durable storage cannot be
// reached by configuring the driver — see ADR 0008's EPIC 24 amendment. What it
// does have is a message protocol, and SQLDelight's `WebWorkerDriver` speaks
// only that protocol, so anything answering it is a valid worker. The four
// actions below (`exec`, `begin_transaction`, `end_transaction`,
// `rollback_transaction`) and the `{ id, results }` / `{ id, error }` reply
// shape are copied from it exactly; `flush` is ours.
//
// THIS IS A CLASSIC WORKER, not a module one. `DatabaseFactory.js.kt` passes
// `{ type: "module" }`, but webpack rewrites that to `{ type: void 0 }` and
// emits a classic chunk that pulls its dependencies in with `importScripts` —
// verified by reading the built `webApp.js`. So `importScripts` is defined
// here, top-level `await` is not, and the `if (typeof importScripts ===
// "function")` guard at the bottom is what actually installs the handler.
// ADR 0008 previously claimed the module type was required on its own merits;
// it is not, and the amendment corrects that.

import initSqlJs from "sql.js";

const DB_NAME = "muviss-db";
const STORE = "snapshot";
const KEY = "muviss.db";

// Coalesces a burst of writes into one export. `markShowAiredSeen` is a single
// transaction over dozens of episodes, so this is usually the difference
// between one snapshot and one per row — `db.export()` serializes the *whole*
// database, which is the cost this whole design trades against durability.
const DEBOUNCE_MS = 500;

let db = null;
let dirty = false;
let debounceTimer = null;
// Only one tab persists. See electWriter().
let isWriter = false;

// --- IndexedDB -------------------------------------------------------------

function openStore() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      if (!req.result.objectStoreNames.contains(STORE)) {
        req.result.createObjectStore(STORE);
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function loadSnapshot() {
  try {
    const idb = await openStore();
    return await new Promise((resolve, reject) => {
      const req = idb.transaction(STORE, "readonly").objectStore(STORE).get(KEY);
      req.onsuccess = () => resolve(req.result ?? null);
      req.onerror = () => reject(req.error);
    });
  } catch (e) {
    // A private window, a browser with site data blocked, or a corrupt store.
    // Starting empty is exactly the old behaviour, so degrade to it rather
    // than failing the whole database.
    console.warn("[muviss] could not read the snapshot; starting empty", e);
    return null;
  }
}

async function saveSnapshot() {
  if (!isWriter || !db) return;
  try {
    const bytes = db.export();
    const idb = await openStore();
    await new Promise((resolve, reject) => {
      const tx = idb.transaction(STORE, "readwrite");
      tx.objectStore(STORE).put(bytes, KEY);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch (e) {
    // A failed snapshot must not fail the query that triggered it: the write
    // is already committed in memory and the tab keeps working. Losing it on
    // reload is the pre-EPIC-24 behaviour, not a new failure.
    console.warn("[muviss] could not write the snapshot", e);
  }
}

function markDirty() {
  dirty = true;
  if (debounceTimer !== null) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    debounceTimer = null;
    if (dirty) {
      dirty = false;
      saveSnapshot();
    }
  }, DEBOUNCE_MS);
}

async function flush() {
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer);
    debounceTimer = null;
  }
  if (dirty) {
    dirty = false;
    await saveSnapshot();
  }
}

// --- Single-writer election ------------------------------------------------

// Two tabs are two workers with two independent in-memory databases and one
// IndexedDB slot, so without this the second tab's `db.export()` overwrites
// the first tab's work wholesale and silently. The lock is held for the life
// of the tab: whoever gets it persists, everyone else runs normally in memory
// and never writes. That loses the loser tab's changes on reload — which is
// what every tab did before EPIC 24, so it is not a regression — but it can
// never destroy data that is already durable.
//
// Web Locks is unavailable in exactly the contexts where nothing is durable
// anyway; there, nobody is the writer and the database is session-only.
function electWriter() {
  if (!navigator.locks || !navigator.locks.request) {
    console.warn("[muviss] no Web Locks; this tab will not persist");
    return;
  }
  navigator.locks.request(DB_NAME, () => {
    isWriter = true;
    // Never resolves: the lock is released when the worker dies with the tab.
    return new Promise(() => {});
  }).catch((e) => console.warn("[muviss] lock request failed", e));
}

// --- Database --------------------------------------------------------------

async function createDatabase() {
  const SQL = await initSqlJs({ locateFile: () => "/sql-wasm.wasm" });
  electWriter();
  const snapshot = await loadSnapshot();
  // `new SQL.Database(bytes)` opens the existing file; the no-arg form creates
  // an empty one. Schema creation and migration are decided on the Kotlin side
  // from `PRAGMA user_version` — see SchemaEnsuringDriver.
  db = snapshot ? new SQL.Database(new Uint8Array(snapshot)) : new SQL.Database();
}

// Everything but a plain SELECT changes bytes on disk. Rather than parse SQL,
// treat anything that is not a read as dirtying — the debounce means the cost
// of being wrong in this direction is one extra export per burst, while being
// wrong the other way loses data.
function isMutation(sql) {
  return !/^\s*(select|pragma\s+\w+\s*$|explain|with\b[\s\S]*\bselect\b)/i.test(sql);
}

function onModuleReady() {
  const data = this.data;

  switch (data && data.action) {
    case "exec": {
      if (!data["sql"]) {
        throw new Error("exec: Missing query string");
      }
      const results = db.exec(data.sql, data.params)[0] ?? { values: [] };
      if (isMutation(data.sql)) markDirty();
      return postMessage({ id: data.id, results });
    }
    case "begin_transaction":
      return postMessage({
        id: data.id,
        results: db.exec("BEGIN TRANSACTION;"),
      });
    case "end_transaction": {
      const results = db.exec("END TRANSACTION;");
      // One snapshot per committed transaction, not per statement inside it.
      markDirty();
      return postMessage({ id: data.id, results });
    }
    case "rollback_transaction":
      return postMessage({
        id: data.id,
        results: db.exec("ROLLBACK TRANSACTION;"),
      });
    // Ours, not SQLDelight's: posted by the main thread on `pagehide`, since a
    // worker cannot observe the page going away. Best-effort — the browser may
    // kill the tab before IndexedDB commits, which is what bounds the loss
    // window at DEBOUNCE_MS of idle rather than at zero.
    case "flush":
      return flush().then(() => postMessage({ id: data.id, results: { values: [] } }));
    default:
      throw new Error(`Unsupported action: ${data && data.action}`);
  }
}

function onError(err) {
  return postMessage({ id: this.data.id, error: err });
}

if (typeof importScripts === "function") {
  db = null;
  const sqlModuleReady = createDatabase();
  self.onmessage = (event) => {
    return sqlModuleReady.then(onModuleReady.bind(event)).catch(onError.bind(event));
  };
}
