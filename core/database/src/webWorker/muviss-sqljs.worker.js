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
//
// THE INVARIANT. Three rules, and every branch below is one of them.
//   1. A tab writes only while it holds the Web Lock.
//   2. Every write is a compare-and-set: in one readwrite transaction it writes
//      only if the stored generation is the one this tab's database was built from
//      or last wrote. So no write can replace a newer snapshot, even when the
//      checks made earlier were skipped (a promotion whose read failed).
//   3. A tab stops saving for good, and says so, as soon as it cannot vouch for its
//      state: an unreadable startup baseline, a moved generation with changes of its
//      own (promote() or the compare-and-set finds it), or a failed transaction
//      boundary (the driver's pointer is then on a dead transaction). A tab with no
//      changes of its own that finds the generation moved reloads instead of
//      stopping, since it has nothing to lose.
// A failed boundary keeps the lock, so the banner says the last changes are not
// saved rather than that another tab holds the database. A stale tab gives the lock
// up so a reload can take it.

import initSqlJs from "sql.js";

const DB_NAME = "muviss-db";
const STORE = "snapshot";
const KEY = "muviss.db";
// The generation of the snapshot in KEY: how many snapshots have been written
// to it. Every save is a compare-and-set against it (#61), so a tab can only
// overwrite a snapshot that is the one it was built from.
const GENERATION_KEY = "muviss.db.generation";

// Coalesces a burst of writes into one export. `markShowAiredSeen` is a single
// transaction over dozens of episodes, so this is usually the difference
// between one snapshot and one per row — `db.export()` serializes the *whole*
// database, which is the cost this whole design trades against durability.
const DEBOUNCE_MS = 500;

// How long to let the election settle before telling the UI this tab is not the
// writer. See electWriter().
const ELECTION_GRACE_MS = 400;

let db = null;
let dirty = false;
let debounceTimer = null;
// Only one tab persists. See electWriter().
let isWriter = false;
// The main thread's end of the persistence channel, and the last thing we had
// to say on it. See announce().
let persistencePort = null;
let lastPersistence = null;
// True from a failed snapshot write until the next one succeeds. Reported on the
// persistence channel so the banner can say the last changes are not saved (#292).
let lastWriteFailed = false;
// True between `begin_transaction` and its `end_`/`rollback_transaction`. A
// SQLDelight transaction spans several messages, so the debounce (or a `flush`)
// can come due inside one, and sql.js 1.15.2 throws from `export()` during an
// open write transaction (#637). A snapshot due then is kept pending (`dirty`)
// and taken once the transaction is over.
let inTransaction = false;
// The generation the in-memory database was built from (0 when there was no
// snapshot). A save is only written while the stored generation still matches.
let loadedGeneration = 0;
// True when the startup read failed, so this database may not be the latest one
// and promotion cannot compare against it. See promote().
let loadFailed = false;
// True once a mutation has changed a row in this database. A tab that has not
// changed one holds nothing the stored snapshot lacks (see promote()). Counted
// by rows changed, not by statements run: a startup write such as `ensureRow`
// is an INSERT OR IGNORE that changes nothing on every launch after the first.
let localWrites = false;
// True when another tab saved after this database was built and this tab has
// changes of its own. It never writes again. See becomeStale().
let stale = false;
// True when this tab is out of date and holds nothing worth keeping: the page is
// reloaded to start from the latest snapshot. See promote().
let reloadRequested = false;
// Releases the Web Lock this tab is holding, if it holds one. See giveUpLock().
let releaseLock = null;
// The startup read, which promotion waits for. See loadSnapshot().
let snapshotReady = null;
// The last promotion, so a test can wait for it. See onLockGranted().
let promotionDone = Promise.resolve();
// True once a transaction boundary (BEGIN, COMMIT or ROLLBACK) has thrown. The
// driver's transaction pointer is then on a transaction that is over, so every
// later write runs with the wrong boundaries, and no snapshot is taken from this
// tab again. See breakTransaction().
let transactionBroken = false;
// The sql.js module, used to open the database at startup.
let sqlEngine = null;

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

// The bytes and their generation, read in one transaction so the pair always
// comes from the same write. A snapshot saved before generations existed has
// bytes and no generation, which reads as generation 0.
async function readSnapshot() {
  const idb = await openStore();
  return await new Promise((resolve, reject) => {
    const tx = idb.transaction(STORE, "readonly");
    const store = tx.objectStore(STORE);
    const bytesReq = store.get(KEY);
    const generationReq = store.get(GENERATION_KEY);
    tx.oncomplete = () => resolve({ bytes: bytesReq.result ?? null, generation: generationReq.result ?? 0 });
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new DOMException("snapshot read aborted", "AbortError"));
  });
}

// Opens the database this tab starts from.
async function loadSnapshot() {
  let snapshot;
  try {
    snapshot = await readSnapshot();
  } catch (e) {
    // A private window, a browser with site data blocked, or a corrupt store.
    // Starting empty is exactly the old behaviour, so degrade to it rather
    // than failing the whole database. Remembered, because an empty database
    // cannot be compared with the stored one when the lock is granted.
    console.warn("[muviss] could not read the snapshot; starting empty", e);
    loadFailed = true;
    snapshot = { bytes: null, generation: 0 };
  }
  loadedGeneration = snapshot.generation;
  // `new SQL.Database(bytes)` opens the existing file; the no-arg form creates
  // an empty one. Schema creation and migration are decided on the Kotlin side
  // from `PRAGMA user_version` — see SchemaEnsuringDriver.
  db = snapshot.bytes ? new sqlEngine.Database(new Uint8Array(snapshot.bytes)) : new sqlEngine.Database();
}

// Snapshot writes run one at a time, in order. The debounce timer does not wait
// for the write it starts, so two could overlap, and a slow older write that
// succeeded after a newer one failed (a full quota) would clear the warning
// while the newest changes were not saved. One at a time, the last one to finish
// is the newest: its outcome is the one the flag reports.
//
// The database is exported when a save is asked for, not when its turn comes: by
// then a transaction may be open, and sql.js 1.15.2 cannot export during one
// (#637). A save asked for inside a transaction exports nothing; it stays
// pending (`dirty`) and the transaction's end or rollback asks again.
//
// While a write is in flight, at most one export waits behind it, and a newer
// one replaces it: every export is the whole database, so the newest holds
// everything the older one did, and a stalled IndexedDB cannot pile copies up.
let saveChain = Promise.resolve();
let saving = false;
let waiting = null; // { bytes, exportError } for the next write, or null

function saveSnapshot() {
  if (!isWriter || !db || stale || loadFailed || transactionBroken) return saveChain;
  if (inTransaction) {
    dirty = true;
    return saveChain;
  }
  let bytes = null;
  let exportError = null;
  try {
    bytes = db.export();
  } catch (e) {
    exportError = e;
  }
  // A failed export keeps the good copy already waiting: it is still written, and
  // the failure is reported after it, since the newest changes are not in it.
  waiting = exportError && waiting?.bytes ? { bytes: waiting.bytes, exportError } : { bytes, exportError };
  if (!saving) {
    saving = true;
    saveChain = (async () => {
      while (waiting) {
        const next = waiting;
        waiting = null;
        await writeSnapshot(next.bytes, next.exportError);
      }
      saving = false;
    })();
  }
  return saveChain;
}

async function writeSnapshot(bytes, exportError) {
  try {
    if (stale) return;
    if (bytes) {
      const outcome = await putSnapshot(bytes);
      if (!outcome.ok) {
        becomeStale();
        return;
      }
      loadedGeneration = outcome.generation;
    }
    if (exportError) throw exportError;
    if (lastWriteFailed) {
      lastWriteFailed = false;
      announce();
    }
  } catch (e) {
    reportFailedWrite(e);
  }
}

// A compare-and-set (#61). The write goes in only if the stored generation is
// still the one this database was built from. Reading and writing in one
// readwrite transaction is what makes it safe: IndexedDB runs such transactions
// one at a time against a store, so no other tab can save between the check and
// the put. A mismatch writes nothing and reports the generation that is stored.
async function putSnapshot(bytes) {
  const idb = await openStore();
  return await new Promise((resolve, reject) => {
    const tx = idb.transaction(STORE, "readwrite");
    const store = tx.objectStore(STORE);
    let outcome = null;
    const generationReq = store.get(GENERATION_KEY);
    generationReq.onsuccess = () => {
      const stored = generationReq.result ?? 0;
      if (stored !== loadedGeneration) {
        outcome = { ok: false, generation: stored };
        return;
      }
      store.put(bytes, KEY);
      store.put(stored + 1, GENERATION_KEY);
      outcome = { ok: true, generation: stored + 1 };
    };
    tx.oncomplete = () => resolve(outcome);
    tx.onerror = () => reject(tx.error);
    // A full quota aborts the transaction rather than failing the request,
    // and may fire only `abort`. Unhandled, this promise never settles and
    // every later snapshot waits behind it in `saveChain`.
    tx.onabort = () => reject(tx.error ?? new DOMException("snapshot write aborted", "AbortError"));
  });
}

function reportFailedWrite(e) {
  // A failed snapshot must not fail the query that triggered it: the write
  // is already committed in memory and the tab keeps working. Losing it on
  // reload is the pre-EPIC-24 behaviour, not a new failure — but the person
  // using it should be told (#292): a full origin quota or a window that
  // evicts storage looks exactly like a working tab otherwise.
  console.warn("[muviss] could not write the snapshot", e);
  if (!lastWriteFailed) {
    lastWriteFailed = true;
    announce();
  }
}

// A transaction boundary threw. SQLDelight's driver sets its current-transaction
// pointer before it sends BEGIN, and moves it past a transaction only after that
// transaction's END or ROLLBACK succeeds. So after a throw the driver believes a
// dead transaction is still open: its next transaction sends no BEGIN, and the
// writes it makes are not the ones its caller asked to roll back. Nothing in the
// worker can repair that. Whether SQLite still holds the transaction open (a
// deferred constraint) or already undid it (a full disk) is not knowable here, and
// no ROLLBACK from here would fix the driver's view. So this tab stops saving for
// good. Its lock is kept, because WriteFailed (not ReadOnlyTab) is the true banner
// for a writer whose last changes are not saved. The Kotlin side has to rebuild
// the driver; that is #884.
function breakTransaction(e) {
  transactionBroken = true;
  dirty = false;
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer);
    debounceTimer = null;
  }
  console.warn("[muviss] a transaction boundary failed; this tab will not save", e);
  lastWriteFailed = true;
  announce();
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

// Tells the main thread what this tab's storage is actually doing, so the UI
// can say so (issue #53).
//
// Over a MessageChannel of our own, NOT the channel SQLDelight's driver uses.
// The first attempt did share it, on the strength of reading the *js*
// `web-worker-driver` klib: `WorkerWrapper.execute` registers a per-request
// listener that compares `event.data.id` and ignores what it does not
// recognise, so an extra message is harmless there. The **wasmJs** driver is a
// different implementation and does not behave that way: `WasmWorkerResponse`
// declares `results` as a non-null external property and materialises it before
// looking at the id, so any message without `results` takes the whole app down:
//
//   NullPointerException: null
//     at ...WasmWorkerResultWithRowCount.<init>
//     at ...results_$external_prop_getter__externalAdapter
//
// A private channel is not a workaround for that, it is the correct shape: the
// driver's protocol is the driver's, and this is not part of it.
//
// `lastPersistence` exists because the election finishes first. `electWriter`
// is called from `createDatabase` before `sqlModuleReady` resolves, and the
// port arrives through the handler that waits on it — so the first announcement
// is always made before there is anywhere to send it.
function announce() {
  lastPersistence = {
    writer: isWriter && !stale,
    supported: hasWebLocks(),
    lastWriteFailed,
    stale,
    reload: reloadRequested,
  };
  if (persistencePort) persistencePort.postMessage(lastPersistence);
}

function hasWebLocks() {
  return Boolean(navigator.locks && navigator.locks.request);
}

// Two tabs are two workers with two independent in-memory databases and one
// IndexedDB slot, so without this the second tab's `db.export()` overwrites the
// first tab's work wholesale and silently. The lock is held for the life of the
// tab: whoever gets it persists, everyone else runs normally in memory and
// never writes. The loser tab's changes are lost on reload — which is what
// every tab did before EPIC 24, so it is not a regression.
//
// THE REQUEST DELIBERATELY QUEUES. `{ ifAvailable: true }` looks like the right
// call — a queued loser is granted the lock the moment the writer's tab closes,
// and then exports a database forked from the snapshot as it stood when *it*
// opened, over everything the writer did since. That is a real bug and it is
// filed. But refusing outright is worse, and measurably so: on an ordinary page
// reload the new worker starts before the outgoing one has been torn down, asks
// while the lock is still held by a tab that is already dying, and — with
// `ifAvailable` — is refused and never asks again. Verified in Chrome: a single
// tab, reloaded once, showed the "open in another tab" banner with no other tab
// open, and `navigator.locks.query()` reported one holder and zero pending. A
// user who reloads would silently stop persisting, permanently. Queuing makes
// that case correct, because the dying worker's lock is released and the queued
// request is granted a moment later.
//
// Queuing has a cost the other way (#61): a granted tab may hold a database
// older than the stored one. onLockGranted() checks for that before the tab
// writes anything.
//
// Web Locks is unavailable in exactly the contexts where nothing is durable
// anyway; there, nobody is the writer and the database is session-only.
function electWriter() {
  if (!hasWebLocks()) {
    console.warn("[muviss] no Web Locks; this tab will not persist");
    announce();
    return;
  }
  navigator.locks
    .request(DB_NAME, () => new Promise((release) => onLockGranted(release)))
    .catch((e) => {
      console.warn("[muviss] lock request failed", e);
      announce();
    });

  // Nothing above says "you are not the writer" — a queued request is simply
  // silent until it is granted, so without this a losing tab would sit in
  // Pending forever and never show the banner.
  //
  // Delayed rather than announced up front, because the winning tab is granted
  // the lock within a few milliseconds and an immediate "not the writer" would
  // flash the banner on every ordinary page load. By the time this fires the
  // winner has already set `isWriter`, so it says nothing.
  setTimeout(() => {
    if (!isWriter) announce();
  }, ELECTION_GRACE_MS);
}

// The lock is granted to a tab that has waited, and this tab's database may be
// older than the stored snapshot: another tab can have saved while this one
// waited, and the other tab has just let go. Before writing anything, the tab
// compares the stored generation with the one its database was built from:
//
//   - the same: nothing was saved since, so this database is the latest one and
//     it may write, including whatever was changed here while it waited;
//   - different, and nothing was changed here: the database holds nothing the
//     stored snapshot lacks, so the page reloads and starts from the latest one
//     (#62). The reload is what a user would do by hand, and it wins the lock
//     at once because this tab gives it up first;
//   - different, and something was changed here: those changes are in neither
//     the stored snapshot nor anything this tab can write without overwriting
//     the other tab's work, so this tab stops saving and says so (#61). The
//     user reloads to start over, which the banner states costs these changes.
//
// If the stored generation cannot be read, nothing here can be decided, so the
// tab takes the lock as it did before. A write is still checked against the
// stored generation, so it still cannot overwrite a newer snapshot.
function onLockGranted(release) {
  releaseLock = release;
  promotionDone = promote();
}

async function promote() {
  try {
    await snapshotReady;
  } catch {
    // The startup failure is reported where it happened. There is no database
    // to promote, so the tab stays as it was.
    return;
  }
  let latest = null;
  try {
    latest = await readSnapshot();
  } catch (e) {
    console.warn("[muviss] could not check the snapshot when promoted", e);
  }
  // A tab whose startup read failed built its database from nothing, so it has no
  // baseline to compare against: whatever snapshot is stored is newer than it.
  if (latest && (loadFailed || latest.generation !== loadedGeneration)) {
    if (localWrites) {
      becomeStale();
    } else {
      reloadRequested = true;
      giveUpLock();
      announce();
    }
    return;
  }
  if (loadFailed) {
    // Neither the startup read nor this one could be read, so there is still no
    // baseline. This tab must not become the writer: writing its empty database
    // would replace a snapshot it never saw. It gives the lock up so a tab that
    // can read the store takes it.
    giveUpLock();
    announce();
    return;
  }
  isWriter = true;
  announce();
  if (localWrites) saveSnapshot();
}

// Another tab saved after this database was built, and this tab has changes that
// are not in it. Saving either way would overwrite the other tab's work, so this
// tab stops writing and lets go of the lock, which lets a fresh tab (or a reload
// of this one) take it. Nothing is written after this, so nothing is lost
// silently: the banner says what a reload costs.
function becomeStale() {
  stale = true;
  isWriter = false;
  dirty = false;
  waiting = null;
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer);
    debounceTimer = null;
  }
  console.warn("[muviss] another tab saved newer changes; this tab will not save");
  giveUpLock();
  announce();
}

// The lock callback never returns while this tab holds it, so letting go is
// resolving the promise it returned. Dying releases it too, which is the normal
// way the lock is given up; this is for a tab that must give it up while alive.
function giveUpLock() {
  if (releaseLock) {
    const release = releaseLock;
    releaseLock = null;
    release();
  }
}

// --- Database --------------------------------------------------------------

// `snapshotReady` is assigned before this returns, so a caller that awaits it
// (a test, or promote()) never sees the variable still null.
function createDatabase() {
  snapshotReady = initSqlJs({ locateFile: () => "/sql-wasm.wasm" }).then((engine) => {
    sqlEngine = engine;
    return loadSnapshot();
  });
  electWriter();
  return snapshotReady;
}

// Everything but a plain SELECT changes bytes on disk. Rather than parse SQL,
// treat anything that is not a read as dirtying — the debounce means the cost
// of being wrong in this direction is one extra export per burst, while being
// wrong the other way loses data.
function isMutation(sql) {
  // More than one statement: the read check below only sees the first one, so a
  // `select 1; delete ...` would look like a read. Treated as a change, which is
  // the safe direction (see above).
  if (/;\s*\S/.test(sql)) return true;
  // A WITH can lead an INSERT, UPDATE or DELETE as well as a SELECT, so it is not
  // a read. It is counted as a change, which costs one extra export at most.
  return !/^\s*(select|pragma\s+\w+\s*$|explain)/i.test(sql);
}

// Rows changed by this connection so far, across every statement it has run. The
// count is taken before and after each `exec`, not read from getRowsModified():
// that reports only the last INSERT, UPDATE or DELETE in a batch (sql.js 1.15.2),
// so an earlier change in the same batch would go unseen.
function totalChanges() {
  return Number(db.exec("SELECT total_changes();")[0].values[0][0]);
}

function onModuleReady() {
  const data = this.data;

  switch (data && data.action) {
    case "exec": {
      if (!data["sql"]) {
        throw new Error("exec: Missing query string");
      }
      // A reload is pending: this tab will not save, and the page reloads from the
      // latest snapshot shortly, which would discard a change made now without the
      // stale warning. Refuse it before it runs, so the caller gets an error rather
      // than a success that is then lost (see promote()).
      if (reloadRequested && isMutation(data.sql)) {
        throw new Error("exec: this tab is reloading to start from the latest snapshot");
      }
      const before = totalChanges();
      let results;
      try {
        results = db.exec(data.sql, data.params)[0] ?? { values: [] };
      } finally {
        // Accounted even when the batch throws: each statement is committed on its
        // own, so the ones before the failing one are in the database already.
        // A statement that changed no row (an INSERT OR IGNORE on a row that
        // exists, say) is not a change this tab holds. See promote().
        if (totalChanges() > before) localWrites = true;
        if (isMutation(data.sql)) markDirty();
      }
      return postMessage({ id: data.id, results });
    }
    case "begin_transaction": {
      // Changes committed before this transaction cannot be exported once it is
      // open, and the page may go away before it ends. Export them now, while
      // export() still works, so a pagehide mid-transaction loses nothing older.
      // A save already waiting exported when it was asked for (see saveSnapshot).
      if (dirty && isWriter) {
        if (debounceTimer !== null) {
          clearTimeout(debounceTimer);
          debounceTimer = null;
        }
        dirty = false;
        saveSnapshot();
      }
      let results;
      try {
        results = db.exec("BEGIN TRANSACTION;");
      } catch (e) {
        breakTransaction(e);
        throw e;
      }
      inTransaction = true;
      return postMessage({ id: data.id, results });
    }
    case "end_transaction": {
      let results;
      try {
        results = db.exec("END TRANSACTION;");
      } catch (e) {
        breakTransaction(e);
        throw e;
      }
      inTransaction = false;
      // One snapshot per committed transaction, not per statement inside it.
      markDirty();
      return postMessage({ id: data.id, results });
    }
    case "rollback_transaction": {
      let results;
      try {
        results = db.exec("ROLLBACK TRANSACTION;");
      } catch (e) {
        breakTransaction(e);
        throw e;
      }
      inTransaction = false;
      // A snapshot that came due during the transaction still owes the writes
      // committed before it.
      if (dirty) markDirty();
      return postMessage({ id: data.id, results });
    }
    // Ours, not SQLDelight's: posted by the main thread on `pagehide`, since a
    // worker cannot observe the page going away. Best-effort — the browser may
    // kill the tab before IndexedDB commits, which is what bounds the loss
    // window at DEBOUNCE_MS of idle rather than at zero.
    case "flush":
      return flush().then(() => postMessage({ id: data.id, results: { values: [] } }));
    // Also ours. Hands us the MessagePort that persistence announcements go
    // out on — see announce(). Deliberately answered with **no** reply on this
    // channel: a reply would be a message the driver has to parse, and the
    // whole point of the separate port is that it does not have to.
    case "muviss_persistence_port":
      persistencePort = this.ports[0];
      if (lastPersistence) persistencePort.postMessage(lastPersistence);
      return;
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
