// Snapshot generations and the hand-over of the write lock (#61, #62).
//
// Each "tab" is a fresh VM realm running the worker, as in snapshot-write.test.mjs.
// Tabs opened here share one IndexedDB and one lock manager, so they behave like
// tabs of one origin: they see each other's saves and queue for the same lock.
// Readwrite transactions run one at a time against the store, as IndexedDB runs
// them, and a transaction's writes land only when it completes. Everything else
// about timing is real.
//
// Run: node --test core/database/src/webWorker/test/snapshot-generation.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const WORKER = new URL("../muviss-sqljs.worker.js", import.meta.url);
const IMPORT = 'import initSqlJs from "sql.js";';
const KEY = "muviss.db";
const GENERATION = "muviss.db.generation";
const source = readFileSync(WORKER, "utf8");
assert.ok(source.includes(IMPORT), "the worker's sql.js import moved; update this test");

const encode = (text) => new TextEncoder().encode(text);
const decode = (bytes) => new TextDecoder().decode(bytes);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// A database whose contents are the text of every statement run against it, so
// a test can read back exactly what a tab saved.
// Runs statements one at a time, as sql.js does inside a batch: each one is
// committed before the next runs, so a statement that fails leaves the ones
// before it in place. A statement marked NO-OP changes no row, as an INSERT OR
// IGNORE on an existing row does; one marked FAIL throws.
class FakeDatabase {
  constructor(bytes) {
    this.text = bytes ? decode(bytes) : "";
    this.changes = 0;
  }
  exec(sql) {
    if (/total_changes\(\)/i.test(sql)) return [{ columns: ["total_changes()"], values: [[this.changes]] }];
    for (const statement of sql.split(";").map((s) => s.trim()).filter(Boolean)) {
      if (/FAIL/.test(statement)) throw new Error("statement failed");
      this.text += `${statement};`;
      if (/\b(insert|update|delete)\b/i.test(statement) && !/NO-OP/.test(statement)) this.changes += 1;
    }
    return [];
  }
  export() {
    return encode(this.text);
  }
  close() {}
}

function makeIndexedDb() {
  const idb = { data: new Map(), chain: Promise.resolve(), failWrites: false, failReads: false };

  // A transaction against the store. Each runs after the previous one completes.
  const transaction = (mode) => {
    const tx = { oncomplete: null, onerror: null, onabort: null, error: null };
    const ops = [];
    const writes = [];
    if (idb.failReads && mode === "readonly") {
      ops.push(() => {
        throw new DOMException("read failed", "UnknownError");
      });
    }
    tx.objectStore = () => ({
      get: (key) => {
        const req = {};
        ops.push(() => {
          req.result = idb.data.get(key);
          req.onsuccess?.();
        });
        return req;
      },
      put: (value, key) => {
        ops.push(() => {
          if (idb.failWrites) throw new DOMException("quota", "QuotaExceededError");
          writes.push([key, value]);
        });
      },
    });
    const run = async () => {
      // A held read waits here, before it reads anything, until the test releases it.
      if (mode === "readonly" && idb.gate) {
        const gate = idb.gate;
        idb.gate = null;
        gate.markStarted();
        await gate.released;
      }
      for (let i = 0; ; ) {
        await new Promise((resolve) => setImmediate(resolve));
        if (i >= ops.length) break;
        try {
          ops[i++]();
        } catch (e) {
          tx.error = e;
          tx.onabort?.();
          return;
        }
      }
      for (const [key, value] of writes) idb.data.set(key, value);
      tx.oncomplete?.();
    };
    idb.chain = idb.chain.then(run).catch(() => {});
    return tx;
  };

  idb.open = () => {
    const req = {};
    queueMicrotask(() => {
      req.result = {
        objectStoreNames: { contains: () => true },
        transaction: (_name, mode) => transaction(mode),
      };
      req.onsuccess?.();
    });
    return req;
  };
  return idb;
}

// Holds the next readonly transaction, which is the promotion's read of the store,
// until `release()`. `started` resolves once that read is waiting, so a test can
// run a write while the read is still pending.
function holdNextRead(idb) {
  let markStarted;
  let release;
  const started = new Promise((resolve) => {
    markStarted = resolve;
  });
  const released = new Promise((resolve) => {
    release = resolve;
  });
  idb.gate = { markStarted, released };
  return { started, release };
}

// Web Locks as the worker uses them: one holder at a time, the rest queue in order,
// and the lock is released when the holder's callback promise settles.
function makeLocks() {
  let held = false;
  const queue = [];
  let grantWaiters = [];
  const next = () => {
    const grant = queue.shift();
    if (grant) grant();
  };
  return {
    // Resolves when the lock is next granted, after the granted callback has run
    // (so the granted tab's promotion has started). Taken before the release that
    // grants it, so the test can wait for exactly that grant.
    nextGrant() {
      return new Promise((resolve) => grantWaiters.push(resolve));
    },
    request(_name, callback) {
      return new Promise((resolve, reject) => {
        const grant = () => {
          held = true;
          Promise.resolve()
            .then(() => {
              const waiters = grantWaiters;
              grantWaiters = [];
              const result = callback();
              waiters.forEach((wake) => wake());
              return result;
            })
            .then(
              (value) => {
                held = false;
                next();
                resolve(value);
              },
              (error) => {
                held = false;
                next();
                reject(error);
              },
            );
        };
        if (held) queue.push(grant);
        else grant();
      });
    },
  };
}

// Starts a tab: a fresh realm running the worker, which starts itself because
// `importScripts` exists. Resolves once its database is open.
// With `engineFails`, the SQL engine never loads (as when its WebAssembly cannot be
// fetched), so the tab has no database and its startup promise rejects.
async function openTab(idb, locks, { engineFails = false } = {}) {
  const sandbox = {
    indexedDB: idb,
    navigator: { locks },
    importScripts() {},
    console: { warn() {}, log() {}, error() {} },
    setTimeout,
    clearTimeout,
    queueMicrotask,
    Promise,
    DOMException,
    postMessage() {},
    FakeSql: { Database: FakeDatabase },
    engineFails,
  };
  const context = vm.createContext(sandbox);
  context.self = context;
  vm.runInContext(
    source.replace(
      IMPORT,
      'const initSqlJs = async () => { if (engineFails) throw new Error("wasm failed"); return FakeSql; };',
    ),
    context,
  );
  const run = (code) => vm.runInContext(code, context);
  // `snapshotReady` is a top-level `let`, so it is visible here; the worker's own
  // `sqlModuleReady` is declared inside the guard that starts it.
  if (engineFails) {
    // A message is what would reach a failed tab; it attaches the handler that
    // turns the startup rejection into a reply, so nothing is left unhandled.
    await run("snapshotReady").catch(() => {});
    run('self.onmessage({ data: { action: "flush", id: 0 } })');
  } else {
    await run("snapshotReady");
  }
  return {
    run,
    exec: (sql) => run(`onModuleReady.call({ data: { action: "exec", sql: ${JSON.stringify(sql)}, id: 1 } })`),
    flush: () => run("flush()"),
    // A closed tab's worker stops writing and its lock is released, as if the
    // tab had gone. Its state is then the state the tests assert on.
    close: () => run("isWriter = false; giveUpLock()"),
    // Plain values only: an object made in the tab's realm has that realm's prototypes.
    state: () =>
      JSON.parse(
        JSON.stringify(run("({ isWriter, stale, reloadRequested, loadedGeneration, localWrites })")),
      ),
    status: () => JSON.parse(JSON.stringify(run("lastPersistence"))),
  };
}

// Lets a promotion, or a save, finish: the fake store works in macrotasks.
const settle = () => sleep(30);

const storedText = (idb) => (idb.data.has(KEY) ? decode(idb.data.get(KEY)) : null);

test("a lone tab saves each change under the next generation, overlapping saves included", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const tab = await openTab(idb, locks);
  await settle();
  assert.equal(tab.state().isWriter, true);

  tab.exec("INSERT A");
  await tab.flush();
  assert.equal(idb.data.get(GENERATION), 1);

  // Two saves in flight at once must not conflict with each other: the second
  // waits for the first and is then written under the generation it produced.
  tab.exec("INSERT B");
  const second = tab.flush();
  tab.exec("INSERT C");
  const third = tab.flush();
  await Promise.all([second, third]);

  assert.equal(tab.state().stale, false);
  assert.equal(idb.data.get(GENERATION), 3);
  assert.match(storedText(idb), /INSERT A;.*INSERT B;.*INSERT C;/s);
});

test("a waiting tab whose snapshot is out of date reloads, and writes nothing", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();

  const b = await openTab(idb, locks);
  await settle();
  assert.equal(b.state().isWriter, false, "b waits for the lock");

  a.exec("INSERT A2");
  await a.flush(); // generation 2, while b still holds generation 1
  const granted = locks.nextGrant();
  a.close();
  await granted;
  await b.run("promotionDone");
  await b.run("saveChain");

  assert.equal(b.state().reloadRequested, true);
  assert.equal(b.status().reload, true, "the page is told to reload");
  assert.equal(b.state().isWriter, false);
  assert.equal(idb.data.get(GENERATION), 2, "b wrote nothing");
  assert.match(storedText(idb), /INSERT A2/);
});

test("once a reload is pending, a write is refused rather than acknowledged and lost", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();

  const b = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A2");
  await a.flush();
  const granted = locks.nextGrant();
  a.close();
  await granted;
  await b.run("promotionDone");
  await b.run("saveChain");
  assert.equal(b.state().reloadRequested, true);

  // The main thread has not reloaded yet: the write must fail, not succeed and vanish.
  assert.throws(() => b.exec("INSERT B-LATE"), /reloading to start from the latest snapshot/);
  assert.equal(b.state().localWrites, false, "a refused write is not recorded as this tab's change");
  assert.doesNotThrow(() => b.exec("SELECT 1"), "reads still run while the page reloads");
  await settle();
  assert.equal(idb.data.get(GENERATION), 2);
  assert.doesNotMatch(storedText(idb), /B-LATE/);
});

test("a batch that starts with a select but writes is a change, and is saved", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("SELECT 1; INSERT A-BEHIND-SELECT");
  assert.equal(a.state().localWrites, true, "the write behind the select counts");
  await a.flush();
  await settle();
  assert.match(storedText(idb), /A-BEHIND-SELECT/);
});

test("a waiting tab with changes of its own gives the lock up rather than overwrite (#61)", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();

  const b = await openTab(idb, locks);
  await settle();
  b.exec("INSERT B-OWN");

  a.exec("INSERT A2");
  await a.flush();
  const granted = locks.nextGrant();
  a.close();
  await granted;
  await b.run("promotionDone");
  await b.run("saveChain");

  assert.equal(b.state().stale, true);
  assert.equal(b.state().isWriter, false);
  assert.equal(b.state().reloadRequested, false, "its changes are not thrown away silently");
  assert.equal(b.status().stale, true);
  assert.equal(idb.data.get(GENERATION), 2);
  assert.doesNotMatch(storedText(idb), /B-OWN/);

  // It stops saving for good...
  b.exec("INSERT B-MORE");
  await b.flush();
  assert.equal(idb.data.get(GENERATION), 2);

  // ...and has let go of the lock, so a fresh tab (what "reload" gives) takes it.
  const c = await openTab(idb, locks);
  await settle();
  assert.equal(c.state().isWriter, true, "the lock is free for the next tab (#62)");
  assert.equal(c.state().loadedGeneration, 2);
});

test("a tab that never held the lock writes nothing to the store", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  assert.equal(a.state().isWriter, true);

  const b = await openTab(idb, locks);
  await settle();
  b.exec("INSERT B");
  await b.flush();

  assert.equal(idb.data.has(GENERATION), false);
  assert.equal(idb.data.has(KEY), false);
});

test("changes made while waiting are saved when the tab is promoted and nothing has changed", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();

  const b = await openTab(idb, locks);
  await settle();
  b.exec("INSERT B-OWN");
  const granted = locks.nextGrant();
  a.close();
  await granted;
  await b.run("promotionDone");
  await b.run("saveChain");

  assert.equal(b.state().isWriter, true);
  assert.equal(idb.data.get(GENERATION), 2);
  assert.match(storedText(idb), /INSERT A;.*B-OWN;/s);
});

// Also the failure #61 is about: a writer whose changes went into a snapshot
// another tab wrote without it (an older build, say) must not overwrite it.
test("a write against a generation that is no longer stored is refused, and the tab stops (#61)", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();

  idb.data.set(KEY, encode("written elsewhere"));
  idb.data.set(GENERATION, 5);

  a.exec("INSERT A2");
  await a.flush();

  assert.equal(a.state().stale, true);
  assert.equal(a.state().isWriter, false);
  assert.equal(storedText(idb), "written elsewhere");
  assert.equal(idb.data.get(GENERATION), 5);

  const c = await openTab(idb, locks);
  await settle();
  assert.equal(c.state().isWriter, true, "the refused tab gave the lock up");
});

// A startup write such as `ensureRow` is an INSERT OR IGNORE that changes nothing
// on every launch after the first. It must not make a tab's copy count as its own.
test("a write that changes no row does not count as this tab's own change", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();

  const b = await openTab(idb, locks);
  await settle();
  b.exec("INSERT NO-OP ensureRow");
  assert.equal(b.state().localWrites, false);

  a.exec("INSERT A2");
  await a.flush();
  const granted = locks.nextGrant();
  a.close();
  await granted;
  await b.run("promotionDone");
  await b.run("saveChain");

  assert.equal(b.state().reloadRequested, true, "out of date and nothing of its own: it reloads");
  assert.equal(b.state().stale, false);
  assert.equal(idb.data.get(GENERATION), 2);
});

// A Library provider refresh rewrites rows with the values they already hold. An
// UPDATE that matches no changed value changes no row, so it must not count as
// this tab's own change either (#841).
test("a provider refresh that changes no row does not count as this tab's own change", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();

  const b = await openTab(idb, locks);
  await settle();
  b.exec("UPDATE NO-OP refreshSnapshot");
  assert.equal(b.state().localWrites, false);

  a.exec("INSERT A2");
  await a.flush();
  const granted = locks.nextGrant();
  a.close();
  await granted;
  await b.run("promotionDone");
  await b.run("saveChain");

  assert.equal(b.state().reloadRequested, true, "out of date and only refreshed: it reloads");
  assert.equal(b.state().stale, false);
});

test("a snapshot saved before generations existed counts as generation 0", async () => {
  const idb = makeIndexedDb();
  idb.data.set(KEY, encode("legacy;"));
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  assert.equal(a.state().loadedGeneration, 0);

  a.exec("INSERT A");
  await a.flush();
  assert.equal(idb.data.get(GENERATION), 1);
  assert.equal(a.state().stale, false);
  assert.match(storedText(idb), /^legacy;INSERT A;/);
});

test("a tab that could not read the snapshot still cannot overwrite it", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();

  idb.failReads = true;
  const b = await openTab(idb, locks);
  await settle();
  assert.equal(b.state().loadedGeneration, 0, "b starts empty");

  const granted = locks.nextGrant();
  a.close();
  await granted;
  await b.run("promotionDone");
  await b.run("saveChain");
  assert.equal(b.state().isWriter, false, "with no baseline and nothing readable to compare, b does not write");
  assert.equal(b.state().reloadRequested, false, "and it has no store it can reload from");

  b.exec("INSERT B");
  await b.flush();
  await settle();
  assert.equal(idb.data.get(GENERATION), 1, "nothing was written over the snapshot");
  assert.doesNotMatch(storedText(idb), /INSERT B/);
});

// --- Adversarial cases for the generation protocol ----------------------------

// A tab that has been told to reload: it waited, the writer saved again, and this
// tab has no changes of its own. Returns the store and the tab.
async function reloadingTab() {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();
  const b = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A2");
  await a.flush();
  const granted = locks.nextGrant();
  a.close();
  await granted;
  await b.run("promotionDone");
  await b.run("saveChain");
  assert.equal(b.state().reloadRequested, true, "the reloading tab has been set up");
  return { idb, b };
}

test("a pagehide flush on a tab that is reloading writes nothing", async () => {
  const { idb, b } = await reloadingTab();
  const before = storedText(idb);
  await b.flush();
  await settle();
  assert.equal(storedText(idb), before);
  assert.equal(idb.data.get(GENERATION), 2);
});

test("a write made while a promotion is being decided makes the tab stale, not silently reloaded", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();
  const b = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A2");
  await a.flush();
  // The promotion reads the store before it decides. The read is held here, so the
  // write below is made while it is still pending, which is the window the decision
  // has to see. (Writing before the lock is handed over would miss it: the grant is
  // a later continuation, so the write would run before the read even starts.)
  const read = holdNextRead(idb);
  const granted = locks.nextGrant();
  a.close();
  await granted;
  await read.started;
  b.exec("INSERT B-RACE");
  read.release();
  await b.run("promotionDone");
  assert.equal(b.state().stale, true, "the change made in the window is not reloaded away");
  assert.equal(b.state().reloadRequested, false);
  assert.doesNotMatch(storedText(idb), /B-RACE/);
});

test("a WITH statement that writes is refused once a reload is pending", async () => {
  const { idb, b } = await reloadingTab();
  assert.throws(
    () => b.exec("WITH c AS (SELECT 1) INSERT INTO t SELECT * FROM c"),
    /reloading to start from the latest snapshot/,
  );
  await settle();
  assert.doesNotMatch(storedText(idb), /INSERT INTO t/);
});

test("a WITH statement that writes is a change, and is saved", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("WITH c AS (SELECT 1) INSERT WITH-WRITE SELECT * FROM c");
  assert.equal(a.state().localWrites, true, "a WITH that writes counts");
  await a.flush();
  await settle();
  assert.match(storedText(idb), /WITH-WRITE/);
});

test("a batch whose later statement fails still records the statements before it", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  assert.throws(() => a.exec("INSERT A-PART; FAIL"), /statement failed/);
  assert.equal(a.state().localWrites, true, "the first statement committed on its own");
  await a.flush();
  await settle();
  assert.match(storedText(idb), /A-PART/, "and it is saved, not left pending");
});

test("a batch that fails before changing any row records nothing", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  assert.throws(() => a.exec("FAIL; INSERT A-NEVER"), /statement failed/);
  assert.equal(a.state().localWrites, false);
});

test("a trailing statement that changes no row does not hide an earlier change in the batch", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A-TRAIL; UPDATE NO-OP t SET id = 2 WHERE 0");
  assert.equal(a.state().localWrites, true, "the INSERT in the batch counts, whatever ran last");
  await a.flush();
  await settle();
  assert.match(storedText(idb), /A-TRAIL/);
});

test("a tab whose startup read failed never writes its empty database over a readable snapshot", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();

  idb.failReads = true; // this tab's startup read fails
  const b = await openTab(idb, locks);
  await settle(); // the startup read runs, and fails, before the store is readable again
  idb.failReads = false;
  b.exec("INSERT B-EMPTY-BASE");
  await settle();
  const granted = locks.nextGrant();
  a.close();
  await granted;
  await b.run("promotionDone");
  await b.run("saveChain");

  assert.equal(b.state().isWriter, false, "no baseline, so no writer");
  assert.equal(b.state().stale, true, "it had changes, and the store is newer than its empty database");
  assert.equal(idb.data.get(GENERATION), 1, "its empty database was never written over the snapshot");
  assert.match(storedText(idb), /INSERT A/);
  assert.doesNotMatch(storedText(idb), /B-EMPTY-BASE/);
});

test("a tab whose startup and promotion reads both fail never writes, and passes the lock on", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();

  idb.failReads = true;
  const b = await openTab(idb, locks);
  await settle();
  a.close();
  await settle(); // b is granted the lock, cannot read the store, and gives it up
  b.exec("INSERT B-NO-BASELINE");
  await b.flush();
  await settle();
  assert.equal(b.state().isWriter, false);
  assert.equal(idb.data.get(GENERATION), 1, "nothing was written by the tab without a baseline");
  assert.doesNotMatch(storedText(idb), /B-NO-BASELINE/);

  idb.failReads = false;
  const c = await openTab(idb, locks);
  await settle();
  assert.equal(c.state().isWriter, true, "a tab that can read the store takes the lock");
  c.exec("INSERT C");
  await c.flush();
  await settle();
  assert.match(storedText(idb), /INSERT C/);
});

// A tab whose SQL engine never loads holds the lock with no database to write. It
// must release it, or every other tab waits behind a writer that cannot write. It
// reports itself as not saving, not as another tab's.
test("a tab whose database never opened gives the lock up, so a tab that can open the store takes it", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const failed = await openTab(idb, locks, { engineFails: true });
  await settle();
  const healthy = await openTab(idb, locks);
  await settle();
  await healthy.run("promotionDone");
  assert.equal(healthy.state().isWriter, true, "the healthy tab takes the lock");
  assert.equal(failed.state().isWriter, false);
  assert.equal(failed.status().supported, false, "the failed tab says it is not saving");
});

// A write that changes no row must not advance the generation, or a tab waiting with
// changes of its own is made stale by a write that changed nothing.
test("a row write that changes no row does not save or advance the generation", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();
  assert.equal(idb.data.get(GENERATION), 1);
  a.exec("INSERT NO-OP A");
  await a.flush();
  await settle();
  assert.equal(idb.data.get(GENERATION), 1, "the no-op write did not advance the generation");
  assert.equal(storedText(idb), "INSERT A;");
});

// The rewatch statistics are WITH ... SELECT reads. While a reload is pending they
// must still run, and must not schedule a save.
test("a WITH ... SELECT read runs while a reload is pending and saves nothing", async () => {
  const { idb, b } = await reloadingTab();
  const before = storedText(idb);
  assert.doesNotThrow(() => b.exec("WITH p AS (SELECT 1 AS n) SELECT n FROM p"), "the read is not refused");
  await b.flush();
  await settle();
  assert.equal(storedText(idb), before);
  assert.equal(idb.data.get(GENERATION), 2);
});

// A tab whose database never opened because IndexedDB cannot be read, and that
// nothing else can read either, is reported as not saving: nothing is holding it
// back, and a reload would not help.
test("a tab that cannot read the store at startup or promotion reports itself as not saving", async () => {
  const idb = makeIndexedDb();
  idb.failReads = true;
  const locks = makeLocks();
  const tab = await openTab(idb, locks);
  await settle();
  await tab.run("promotionDone");
  assert.equal(tab.state().isWriter, false);
  assert.equal(tab.status().supported, false, "not saving, rather than read-only behind another tab");
});

// Two tabs: the writer completes a transaction that changes nothing and lets go of the
// lock. The tab waiting with changes of its own must still save once promoted. Before
// the fix, the empty transaction advanced the generation, and the waiting tab became
// stale although nothing was written.
test("a writer's empty transaction does not make a waiting tab with changes stale", async () => {
  const idb = makeIndexedDb();
  const locks = makeLocks();
  const a = await openTab(idb, locks);
  await settle();
  a.exec("INSERT A");
  await a.flush();
  const b = await openTab(idb, locks);
  await settle();
  b.exec("INSERT B");
  a.run('onModuleReady.call({ data: { action: "begin_transaction", id: 2 } })');
  a.run('onModuleReady.call({ data: { action: "end_transaction", id: 3 } })');
  await a.flush();
  await settle();
  assert.equal(idb.data.get(GENERATION), 1, "the empty transaction did not advance the generation");
  const granted = locks.nextGrant();
  a.close();
  await granted;
  await b.run("promotionDone");
  await b.run("saveChain");
  assert.equal(b.state().isWriter, true, "B takes the lock");
  assert.equal(b.state().stale, false, "B is not stale");
  assert.match(storedText(idb), /INSERT B/, "B's change is saved");
});
