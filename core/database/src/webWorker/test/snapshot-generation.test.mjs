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
class FakeDatabase {
  constructor(bytes) {
    this.text = bytes ? decode(bytes) : "";
    this.rowsModified = 0;
  }
  // A statement marked NO-OP changes no row, as an INSERT OR IGNORE on an existing row does.
  exec(sql) {
    this.text += `${sql};`;
    this.rowsModified = /NO-OP/.test(sql) ? 0 : 1;
    return [];
  }
  getRowsModified() {
    return this.rowsModified;
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

// Web Locks as the worker uses them: one holder at a time, the rest queue in order,
// and the lock is released when the holder's callback promise settles.
function makeLocks() {
  let held = false;
  const queue = [];
  const next = () => {
    const grant = queue.shift();
    if (grant) grant();
  };
  return {
    request(_name, callback) {
      return new Promise((resolve, reject) => {
        const grant = () => {
          held = true;
          Promise.resolve()
            .then(callback)
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
async function openTab(idb, locks) {
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
  };
  const context = vm.createContext(sandbox);
  context.self = context;
  vm.runInContext(source.replace(IMPORT, "const initSqlJs = async () => FakeSql;"), context);
  const run = (code) => vm.runInContext(code, context);
  // `snapshotReady` is a top-level `let`, so it is visible here; the worker's own
  // `sqlModuleReady` is declared inside the guard that starts it.
  await run("snapshotReady");
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
  a.close();
  await settle();

  assert.equal(b.state().reloadRequested, true);
  assert.equal(b.status().reload, true, "the page is told to reload");
  assert.equal(b.state().isWriter, false);
  assert.equal(idb.data.get(GENERATION), 2, "b wrote nothing");
  assert.match(storedText(idb), /INSERT A2/);
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
  a.close();
  await settle();

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
  a.close();
  await settle();

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
  a.close();
  await settle();

  assert.equal(b.state().reloadRequested, true, "out of date and nothing of its own: it reloads");
  assert.equal(b.state().stale, false);
  assert.equal(idb.data.get(GENERATION), 2);
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
  assert.equal(b.state().loadedGeneration, 0, "b starts empty, as it did before");

  a.close();
  await settle();
  assert.equal(b.state().isWriter, true, "with nothing readable to compare, the lock is taken as before");
  assert.equal(b.state().reloadRequested, false);

  b.exec("INSERT B");
  await b.flush();
  assert.equal(b.state().stale, true, "the write is checked against the stored generation");
  assert.equal(idb.data.get(GENERATION), 1);
  assert.doesNotMatch(storedText(idb), /INSERT B/);
});
