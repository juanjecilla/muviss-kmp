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

// An IndexedDB whose `put` fails when `failing` is true as it settles, and
// whose transaction only aborts (as a full quota does) when `aborting` is. With
// `throwing`, `put` itself throws synchronously instead, as a DataCloneError
// does. With `hold`, each put waits until the test releases it, to make writes
// overlap.
function fakeIndexedDb() {
  const fake = { failing: false, aborting: false, throwing: false, puts: [], holds: [] };
  fake.open = () => {
    const req = {};
    queueMicrotask(() => {
      req.result = {
        objectStoreNames: { contains: () => true },
        transaction: () => {
          const tx = {
            objectStore: () => ({
              put: (bytes) => {
                if (fake.throwing) throw new Error("DataCloneError");
                const settle = () => {
                  if (fake.aborting) {
                    tx.error = new Error("QuotaExceededError");
                    tx.onabort?.();
                  } else if (fake.failing) {
                    tx.error = new Error("QuotaExceededError");
                    tx.onerror();
                  } else {
                    fake.puts.push(bytes);
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

function loadWorker() {
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
  Object.assign(context, { __version: { n: 0 }, __post: (m) => announced.push({ ...m }) });
  vm.runInContext(
    "isWriter = true; db = { export: () => `snapshot ${++__version.n}` };" +
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
  assert.deepEqual(announced.at(-1), { writer: true, supported: true, lastWriteFailed: true });

  indexedDB.failing = false;
  await run("saveSnapshot()");
  assert.deepEqual(announced.at(-1), { writer: true, supported: true, lastWriteFailed: false });
  assert.equal(indexedDB.puts.length, 1);
});

test("a put that throws synchronously is a failed write, and the next good one clears it", async () => {
  const { indexedDB, announced, run } = loadWorker();

  indexedDB.throwing = true;
  await run("saveSnapshot()");
  assert.deepEqual(announced.at(-1), { writer: true, supported: true, lastWriteFailed: true });

  indexedDB.throwing = false;
  await run("saveSnapshot()");
  assert.deepEqual(announced.at(-1), { writer: true, supported: true, lastWriteFailed: false });
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
  await older;
  indexedDB.failing = true;
  await new Promise((r) => setTimeout(r, 10));
  indexedDB.holds.shift()(); // the newer write fails
  await newer;

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
    "db = { exec: (sql) => { if (/^BEGIN/.test(sql)) db.open = true; if (/^(END|ROLLBACK)/.test(sql)) db.open = false; return []; }," +
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
  run("db.exec = () => []; postMessage = () => {};");
  run('onModuleReady.call({ data: { action: "begin_transaction", id: 1 } })');
  await run("saveSnapshot()");
  run('onModuleReady.call({ data: { action: "rollback_transaction", id: 2 } })');
  await run("flush()");
  assert.deepEqual(indexedDB.puts, ["snapshot 1"]);
});

test("changes committed before a transaction are saved before it opens", async () => {
  const { indexedDB, announced, run } = loadWorker();
  run(
    "db = { exec: (sql) => { if (/^BEGIN/.test(sql)) db.open = true; if (/^(END|ROLLBACK)/.test(sql)) db.open = false; return []; }," +
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
    "db = { exec: (sql) => { if (/^BEGIN/.test(sql)) db.open = true; if (/^(END|ROLLBACK)/.test(sql)) db.open = false; return []; }," +
      " export: () => { if (db.open) throw new Error('cannot export during a transaction'); return `snapshot ${++__version.n}`; } };" +
      "postMessage = () => {};",
  );
  indexedDB.hold = true;
  const first = run("saveSnapshot()"); // a slow write in flight
  await new Promise((r) => setTimeout(r, 10));
  const second = run("saveSnapshot()"); // the debounce fires again: queued behind it
  run('onModuleReady.call({ data: { action: "begin_transaction", id: 1 } })');
  indexedDB.holds.shift()();
  await first;
  await new Promise((r) => setTimeout(r, 10));
  indexedDB.holds.shift()();
  await second;
  assert.deepEqual(indexedDB.puts, ["snapshot 1", "snapshot 2"]);
  assert.equal(announced.length, 0);
});
