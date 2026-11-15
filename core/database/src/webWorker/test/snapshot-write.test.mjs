// Snapshot-write failures reach the persistence channel (#292).
//
// The worker is one classic-worker script with no exports, so it is loaded the
// way a browser would run it: into a fresh realm with the browser globals it
// touches faked. Its `import` of sql.js is the one line that cannot run there;
// it is swapped for a stub, since nothing here executes SQL. Top-level `let`s
// are shared by every script run in the same realm, which is how the test sets
// the worker's state and calls its functions.
//
// Run: node --test core/database/src/webWorker/test/snapshot-write.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const WORKER = new URL("../muviss-sqljs.worker.js", import.meta.url);
const IMPORT = 'import initSqlJs from "sql.js";';
const KEY = "muviss.db";

// An IndexedDB whose `put` fails when `failing` is true as it settles, and
// whose transaction only aborts (as a full quota does) when `aborting` is. With
// `throwing`, `put` itself throws synchronously instead, as a DataCloneError
// does. With `hold`, each put waits until the test releases it, to make writes
// overlap.
// The generation record is read by `get` and written by `put`, like the snapshot
// itself. Only the snapshot's put is a write that can fail or be held, so the
// generation put rides along with it and is applied only when the snapshot
// write succeeds.
function fakeIndexedDb() {
  const fake = { failing: false, aborting: false, throwing: false, puts: [], holds: [], generation: 0 };
  fake.open = () => {
    const req = {};
    queueMicrotask(() => {
      req.result = {
        objectStoreNames: { contains: () => true },
        transaction: () => {
          const tx = {
            objectStore: () => ({
              get: () => {
                const getReq = {};
                queueMicrotask(() => {
                  getReq.result = fake.generation;
                  // As in a browser, an exception from a request handler aborts the transaction.
                  try {
                    getReq.onsuccess();
                  } catch (e) {
                    tx.error = e;
                    tx.onabort?.();
                  }
                });
                return getReq;
              },
              put: (value, key) => {
                if (key !== KEY) {
                  tx.nextGeneration = value;
                  return;
                }
                if (fake.throwing) throw new Error("DataCloneError");
                const settle = () => {
                  if (fake.aborting) {
                    tx.error = new Error("QuotaExceededError");
                    tx.onabort?.();
                  } else if (fake.failing) {
                    tx.error = new Error("QuotaExceededError");
                    tx.onerror();
                  } else {
                    fake.puts.push(value);
                    fake.generation = tx.nextGeneration;
                    tx.oncomplete();
                  }
                };
                if (fake.hold) fake.holds.push(settle);
                else queueMicrotask(settle);
              },
            }),
          };
          return tx;
        },
      };
      req.onsuccess();
    });
    return req;
  };
  return fake;
}

// `sqlite`, when given, is the database the worker runs against (see fakeSqlite).
function loadWorker(sqlite = null) {
  const source = readFileSync(WORKER, "utf8");
  assert.ok(source.includes(IMPORT), "the worker's sql.js import moved; update this test");
  const indexedDB = fakeIndexedDb();
  const announced = [];
  const context = vm.createContext({
    indexedDB,
    navigator: { locks: { request: () => new Promise(() => {}) } },
    console: { warn() {}, log() {}, error() {} },
    setTimeout,
    clearTimeout,
    queueMicrotask,
    Promise,
    DOMException,
  });
  vm.runInContext(source.replace(IMPORT, "const initSqlJs = async () => ({});"), context);
  // Copied on this side: an object made in the worker's realm has that realm's
  // Object.prototype, and deepEqual would refuse it.
  Object.assign(context, { __version: { n: 0 }, __post: (m) => announced.push({ ...m }), __sqlite: sqlite });
  vm.runInContext(
    "isWriter = true; postMessage = () => {};" +
      `db = ${sqlite ? "__sqlite" : "{ export: () => `snapshot ${++__version.n}` }"};` +
      "persistencePort = { postMessage: __post };",
    context,
  );
  const run = (code) => vm.runInContext(code, context);
  return { indexedDB, announced, run };
}

test("a failed put is announced, and the next successful write clears it", async () => {
  const { indexedDB, announced, run } = loadWorker();

  indexedDB.failing = true;
  await run("saveSnapshot()");
  assert.deepEqual(announced.at(-1), { writer: true, supported: true, lastWriteFailed: true, stale: false, reload: false });

  indexedDB.failing = false;
  await run("saveSnapshot()");
  assert.deepEqual(announced.at(-1), { writer: true, supported: true, lastWriteFailed: false, stale: false, reload: false });
  assert.equal(indexedDB.puts.length, 1);
});

test("a put that throws synchronously is a failed write, and the next good one clears it", async () => {
  const { indexedDB, announced, run } = loadWorker();

  indexedDB.throwing = true;
  await run("saveSnapshot()");
  assert.deepEqual(announced.at(-1), { writer: true, supported: true, lastWriteFailed: true, stale: false, reload: false });

  indexedDB.throwing = false;
  await run("saveSnapshot()");
  assert.deepEqual(announced.at(-1), { writer: true, supported: true, lastWriteFailed: false, stale: false, reload: false });
  assert.equal(indexedDB.puts.length, 1);
});

test("a second failure in a row is not announced again", async () => {
  const { indexedDB, announced, run } = loadWorker();
  indexedDB.failing = true;
  await run("saveSnapshot()");
  await run("saveSnapshot()");
  assert.equal(announced.length, 1);
});

test("writes run in order, so an older write cannot clear a newer failure", async () => {
  const { indexedDB, announced, run } = loadWorker();
  indexedDB.hold = true;

  // The older write will succeed, the newer one will fail. Unserialized, the
  // newer put could be issued first and the older success would land last.
  const older = run("saveSnapshot()");
  const newer = run("saveSnapshot()");
  await new Promise((r) => setTimeout(r, 10));
  assert.equal(indexedDB.holds.length, 1, "the newer write started before the older one finished");

  indexedDB.holds.shift()(); // the older write succeeds
  await new Promise((r) => setTimeout(r, 10));
  indexedDB.failing = true;
  await new Promise((r) => setTimeout(r, 10));
  indexedDB.holds.shift()(); // the newer write fails
  await Promise.all([older, newer]);

  assert.deepEqual(indexedDB.puts, ["snapshot 1"]);
  assert.equal(announced.at(-1).lastWriteFailed, true);
});

test("an aborted transaction is a failed write, and does not block the next one", async () => {
  const { indexedDB, announced, run } = loadWorker();
  const settled = (p) =>
    Promise.race([p, new Promise((_, reject) => setTimeout(() => reject(new Error("write never settled")), 200))]);

  indexedDB.aborting = true;
  await settled(run("saveSnapshot()"));
  assert.equal(announced.at(-1).lastWriteFailed, true);

  indexedDB.aborting = false;
  await settled(run("saveSnapshot()"));
  assert.equal(announced.at(-1).lastWriteFailed, false);
  assert.deepEqual(indexedDB.puts, ["snapshot 2"]);
});

// #637: sql.js 1.15.2 throws from export() inside an open write transaction. A
// snapshot that comes due then must wait for the transaction, not fail.
test("a snapshot due inside a transaction waits for it instead of failing", async () => {
  const { indexedDB, announced, run } = loadWorker();
  run(
    "db = { exec: (sql) => { if (/^BEGIN/.test(sql)) db.open = true; if (/^(END|ROLLBACK)/.test(sql)) db.open = false; return /total_changes/.test(sql) ? [{ values: [[0]] }] : []; }," +
      " export: () => { if (db.open) throw new Error('cannot export during a transaction'); return `snapshot ${++__version.n}`; } };" +
      "postMessage = () => {};",
  );
  run('onModuleReady.call({ data: { action: "begin_transaction", id: 1 } })');
  await run("saveSnapshot()");
  assert.equal(indexedDB.puts.length, 0, "nothing exported mid-transaction");
  assert.equal(announced.length, 0, "and no unsaved-changes banner");

  run('onModuleReady.call({ data: { action: "end_transaction", id: 2 } })');
  await run("flush()");
  assert.deepEqual(indexedDB.puts, ["snapshot 1"]);
  assert.equal(announced.length, 0);
});

test("a snapshot due inside a rolled-back transaction is still taken", async () => {
  const { indexedDB, run } = loadWorker();
  run("db.exec = (sql) => (/total_changes/.test(sql) ? [{ values: [[0]] }] : []); postMessage = () => {};");
  run('onModuleReady.call({ data: { action: "begin_transaction", id: 1 } })');
  await run("saveSnapshot()");
  run('onModuleReady.call({ data: { action: "rollback_transaction", id: 2 } })');
  await run("flush()");
  assert.deepEqual(indexedDB.puts, ["snapshot 1"]);
});

// A stand-in for the SQLite connection, with the behaviour the worker depends on:
// a transaction holds its writes until END, a failed END may leave the transaction
// open (a deferred constraint) or undo it (a full disk, or an I/O error), and
// export() cannot run inside an open transaction. `fail` names the next boundary to
// throw, once; `undone` makes the throw also undo the transaction.
function fakeSqlite({ begin = false, end = false, rollback = false, undone = false } = {}) {
  const faults = { begin, end, rollback };
  const undo = (sqlite) => {
    sqlite.open = false;
    sqlite.pending = [];
  };
  const sqlite = {
    open: false,
    pending: [],
    committed: [],
    exec(sql) {
      const verb = sql.trim().split(/\s+/)[0].toUpperCase();
      if (verb === "BEGIN") {
        if (faults.begin) {
          faults.begin = false;
          throw new Error("begin failed");
        }
        sqlite.open = true;
        return [];
      }
      if (verb === "END") {
        if (faults.end) {
          faults.end = false;
          if (undone) undo(sqlite);
          throw new Error("end failed");
        }
        sqlite.committed.push(...sqlite.pending);
        undo(sqlite);
        return [];
      }
      if (verb === "ROLLBACK") {
        if (faults.rollback) {
          faults.rollback = false;
          if (undone) undo(sqlite);
          throw new Error("rollback failed");
        }
        undo(sqlite);
        return [];
      }
      if (/total_changes/i.test(sql)) return [{ values: [[0]] }];
      if (sqlite.open) sqlite.pending.push(sql);
      else sqlite.committed.push(sql);
      return [];
    },
    // The stored snapshot is the committed text, so a test can read what was saved.
    export() {
      if (sqlite.open) throw new Error("cannot export during a transaction");
      return sqlite.committed.join(";");
    },
    close() {},
  };
  return sqlite;
}

// The Kotlin side, as SQLDelight 2.4.1's WebWorkerDriver runs its transactions
// (read from the compiled driver): a transaction moves the driver's current
// pointer to itself before it sends BEGIN, sends BEGIN only when it has no
// enclosing transaction, sends END or ROLLBACK only from the outermost one, and
// moves the pointer back only when that send succeeds. So after a throw the
// pointer stays on a transaction that is over, and the next transaction is nested.
function kotlinDriver(run) {
  const send = (action, sql) =>
    run(`onModuleReady.call({ data: { action: ${JSON.stringify(action)}, sql: ${JSON.stringify(sql ?? "")}, id: 1 } })`);
  let current = null;
  return {
    begin() {
      const tx = { enclosing: current };
      current = tx;
      if (tx.enclosing === null) send("begin_transaction");
      return tx;
    },
    end(tx, successful) {
      if (tx.enclosing === null) send(successful ? "end_transaction" : "rollback_transaction");
      current = tx.enclosing;
    },
    exec: (sql) => send("exec", sql),
  };
}

// A COMMIT that throws leaves the driver's pointer on a transaction that is over.
// Its next transaction is nested: no BEGIN, and its writes run outside any
// transaction. Those must not reach the store, and the failure must be announced.
test("a COMMIT that throws keeps the tab from saving anything after it", async () => {
  const { indexedDB, announced, run } = loadWorker(fakeSqlite({ end: true }));
  const driver = kotlinDriver(run);
  const t1 = driver.begin();
  driver.exec("UPDATE t SET x = 1");
  assert.throws(() => driver.end(t1, true), /end failed/);
  const t2 = driver.begin();
  driver.exec("INSERT B");
  driver.end(t2, false);
  await run("flush()");
  assert.deepEqual(indexedDB.puts, []);
  assert.equal(announced.at(-1).lastWriteFailed, true);
});

// A ROLLBACK that throws after SQLite already undid the transaction: the nested
// transaction's writes then autocommit. Nothing autocommitted may be saved either.
test("a ROLLBACK that throws keeps the tab from saving the writes that follow it", async () => {
  const { indexedDB, announced, run } = loadWorker(fakeSqlite({ rollback: true, undone: true }));
  const driver = kotlinDriver(run);
  const t1 = driver.begin();
  driver.exec("UPDATE t SET x = 1");
  assert.throws(() => driver.end(t1, false), /rollback failed/);
  const t2 = driver.begin();
  driver.exec("INSERT B");
  driver.end(t2, true);
  await run("flush()");
  assert.deepEqual(indexedDB.puts, []);
  assert.equal(announced.at(-1).lastWriteFailed, true);
});

// A BEGIN that throws: the pointer is already on the transaction, so the next one is
// nested and runs without a transaction. Its writes must not be saved either.
test("a BEGIN that throws keeps the tab from saving the writes that follow it", async () => {
  const { indexedDB, announced, run } = loadWorker(fakeSqlite({ begin: true }));
  const driver = kotlinDriver(run);
  assert.throws(() => driver.begin(), /begin failed/);
  const t2 = driver.begin();
  driver.exec("INSERT B");
  driver.end(t2, true);
  await run("flush()");
  assert.deepEqual(indexedDB.puts, []);
  assert.equal(announced.at(-1).lastWriteFailed, true);
});

test("changes committed before a transaction are saved before it opens", async () => {
  const { indexedDB, announced, run } = loadWorker();
  run(
    "db = { exec: (sql) => { if (/^BEGIN/.test(sql)) db.open = true; if (/^(END|ROLLBACK)/.test(sql)) db.open = false; return /total_changes/.test(sql) ? [{ values: [[0]] }] : []; }," +
      " export: () => { if (db.open) throw new Error('cannot export during a transaction'); return `snapshot ${++__version.n}`; } };" +
      "postMessage = () => {};",
  );
  run('onModuleReady.call({ data: { action: "exec", sql: "UPDATE t SET x = 1", id: 1 } })');
  run('onModuleReady.call({ data: { action: "begin_transaction", id: 2 } })');
  // The page goes away here, mid-transaction: the earlier write must already be on its way.
  await run("saveChain");
  assert.deepEqual(indexedDB.puts, ["snapshot 1"]);
  assert.equal(announced.length, 0);
});

test("a save queued behind a slow one still holds the changes made before a transaction", async () => {
  const { indexedDB, announced, run } = loadWorker();
  run(
    "db = { exec: (sql) => { if (/^BEGIN/.test(sql)) db.open = true; if (/^(END|ROLLBACK)/.test(sql)) db.open = false; return /total_changes/.test(sql) ? [{ values: [[0]] }] : []; }," +
      " export: () => { if (db.open) throw new Error('cannot export during a transaction'); return `snapshot ${++__version.n}`; } };" +
      "postMessage = () => {};",
  );
  indexedDB.hold = true;
  const first = run("saveSnapshot()"); // a slow write in flight
  await new Promise((r) => setTimeout(r, 10));
  const second = run("saveSnapshot()"); // the debounce fires again: queued behind it
  run('onModuleReady.call({ data: { action: "begin_transaction", id: 1 } })');
  indexedDB.holds.shift()();
  await new Promise((r) => setTimeout(r, 10));
  indexedDB.holds.shift()();
  await Promise.all([first, second]);
  assert.deepEqual(indexedDB.puts, ["snapshot 1", "snapshot 2"]);
  assert.equal(announced.length, 0);
});

test("a stalled write keeps one export waiting, the newest", async () => {
  const { indexedDB, run } = loadWorker();
  indexedDB.hold = true;
  const done = run("saveSnapshot()"); // stalls
  await new Promise((r) => setTimeout(r, 10));
  for (let i = 0; i < 5; i++) run("saveSnapshot()"); // five more debounces meanwhile
  assert.equal(run("waiting.bytes"), "snapshot 6", "only the newest export is kept");
  indexedDB.holds.shift()();
  await new Promise((r) => setTimeout(r, 10));
  indexedDB.holds.shift()();
  await done;
  assert.deepEqual(indexedDB.puts, ["snapshot 1", "snapshot 6"]);
});

test("a failed export keeps the good copy waiting and still reports the failure", async () => {
  const { indexedDB, announced, run } = loadWorker();
  indexedDB.hold = true;
  const done = run("saveSnapshot()"); // snapshot 1, stalls
  await new Promise((r) => setTimeout(r, 10));
  run("saveSnapshot()"); // snapshot 2 waits
  run("db.export = () => { throw new Error('export failed'); }; saveSnapshot()");
  indexedDB.holds.shift()();
  await new Promise((r) => setTimeout(r, 10));
  indexedDB.holds.shift()();
  await done;
  assert.deepEqual(indexedDB.puts, ["snapshot 1", "snapshot 2"]);
  assert.equal(announced.at(-1).lastWriteFailed, true);
});
