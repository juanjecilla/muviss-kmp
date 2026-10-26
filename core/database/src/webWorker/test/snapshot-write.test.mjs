// Snapshot-write failures reach the persistence channel (#292).
//
// The worker is one classic-worker script with no exports, so it is loaded the
// way a browser would run it: into a fresh realm with the browser globals it
// touches faked. Its `import` of sql.js is the one line that cannot run there;
// it is swapped for a stub, since nothing here executes SQL. Top-level `let`s
// are shared by every script run in the same realm, which is how the test sets
// the worker's state and calls its functions.
//
// Run: node --test core/database/src/webWorker/test/
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const WORKER = new URL("../muviss-sqljs.worker.js", import.meta.url);
const IMPORT = 'import initSqlJs from "sql.js";';

// An IndexedDB whose `put` fails when `failing` is true as it settles. With
// `hold`, each put waits until the test releases it, to make writes overlap.
function fakeIndexedDb() {
  const fake = { failing: false, puts: [], holds: [] };
  fake.open = () => {
    const req = {};
    queueMicrotask(() => {
      req.result = {
        objectStoreNames: { contains: () => true },
        transaction: () => {
          const tx = {
            objectStore: () => ({
              put: (bytes) => {
                const settle = () => {
                  if (fake.failing) {
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
