// deno test supabase/functions  (see docs/SYNC.md "Entitlement")
import { assert, assertEquals } from "jsr:@std/assert@1.0.13";
import { allowedEnvironments, authorized, plan, transferRows } from "./logic.ts";
import { type EntitlementStore, handle } from "./handler.ts";

const U1 = "00000000-0000-0000-0000-0000000000a1";
const U2 = "00000000-0000-0000-0000-0000000000a2";
const PROD = allowedEnvironments(undefined);
const T = Date.UTC(2026, 9, 10, 12, 0, 0);
const EXP = Date.UTC(2026, 10, 10, 12, 0, 0);

function ev(fields: Record<string, unknown>) {
  return {
    api_version: "1.0",
    event: {
      id: "evt-1",
      event_timestamp_ms: T,
      app_user_id: U1,
      entitlement_ids: ["sync"],
      environment: "PRODUCTION",
      expiration_at_ms: EXP,
      ...fields,
    },
  };
}

Deno.test("authorized: only the exact secret passes", async () => {
  assert(await authorized("s3cret", "s3cret"));
  assert(!(await authorized("s3cre", "s3cret")));
  assert(!(await authorized("Bearer s3cret", "s3cret")));
  assert(!(await authorized(null, "s3cret")));
  assert(!(await authorized("", "")));
  assert(!(await authorized("anything", undefined)));
});

Deno.test("allowedEnvironments defaults to PRODUCTION", () => {
  assertEquals([...PROD], ["PRODUCTION"]);
  assertEquals([...allowedEnvironments(" sandbox , PRODUCTION")], ["SANDBOX", "PRODUCTION"]);
  assertEquals([...allowedEnvironments(",")], ["PRODUCTION"]);
});

Deno.test("a purchase grants until its expiration", () => {
  assertEquals(plan(ev({ type: "INITIAL_PURCHASE" }), PROD), {
    kind: "write",
    rows: [{
      user_id: U1,
      active: true,
      expires_at: new Date(EXP).toISOString(),
      source_event: "INITIAL_PURCHASE:evt-1",
      source_event_at: new Date(T).toISOString(),
    }],
  });
});

Deno.test("a grant with no expiration has no end", () => {
  const p = plan(ev({ type: "NON_RENEWING_PURCHASE", expiration_at_ms: null }), PROD);
  assert(p.kind === "write");
  assertEquals(p.rows[0].active, true);
  assertEquals(p.rows[0].expires_at, null);
});

Deno.test("renewal, uncancellation, extension and product change all grant", () => {
  for (const type of ["RENEWAL", "UNCANCELLATION", "SUBSCRIPTION_EXTENDED", "PRODUCT_CHANGE", "TEMPORARY_ENTITLEMENT_GRANT"]) {
    const p = plan(ev({ type }), PROD);
    assert(p.kind === "write", type);
    assertEquals(p.rows[0].active, true, type);
  }
});

Deno.test("expiration revokes", () => {
  const p = plan(ev({ type: "EXPIRATION", expiration_at_ms: T }), PROD);
  assert(p.kind === "write");
  assertEquals(p.rows[0].active, false);
});

Deno.test("cancellation keeps access until the period ends", () => {
  const p = plan(ev({ type: "CANCELLATION" }), PROD);
  assert(p.kind === "write");
  assertEquals(p.rows[0].active, true);
  assertEquals(p.rows[0].expires_at, new Date(EXP).toISOString());
});

Deno.test("a cancellation with no expiration ends access now, never forever", () => {
  const p = plan(ev({ type: "CANCELLATION", expiration_at_ms: null }), PROD);
  assert(p.kind === "write");
  assertEquals(p.rows[0].active, false);
  assertEquals(p.rows[0].expires_at, new Date(T).toISOString());
});

Deno.test("events that do not change access are ignored", () => {
  for (const type of ["TEST", "SUBSCRIPTION_PAUSED", "SUBSCRIBER_ALIAS", "EXPERIMENT_ENROLLMENT", "SOMETHING_NEW"]) {
    assertEquals(plan(ev({ type }), PROD).kind, "ignore", type);
  }
});

Deno.test("other entitlements are ignored", () => {
  assertEquals(plan(ev({ type: "INITIAL_PURCHASE", entitlement_ids: ["pro"] }), PROD).kind, "ignore");
  // Deprecated singular field, consulted only without the array.
  assertEquals(plan(ev({ type: "INITIAL_PURCHASE", entitlement_ids: undefined, entitlement_id: "sync" }), PROD).kind, "write");
});

Deno.test("sandbox is ignored unless allowed", () => {
  assertEquals(plan(ev({ type: "INITIAL_PURCHASE", environment: "SANDBOX" }), PROD).kind, "ignore");
  assertEquals(plan(ev({ type: "INITIAL_PURCHASE", environment: "SANDBOX" }), allowedEnvironments("SANDBOX")).kind, "write");
});

Deno.test("a non-uuid app_user_id is rejected", () => {
  const p = plan(ev({ type: "INITIAL_PURCHASE", app_user_id: "$RCAnonymousID:abc" }), PROD);
  assertEquals(p.kind, "reject");
});

Deno.test("malformed bodies are rejected", () => {
  assertEquals(plan(null, PROD).kind, "reject");
  assertEquals(plan({}, PROD).kind, "reject");
  assertEquals(plan({ event: {} }, PROD).kind, "reject");
  assertEquals(plan(ev({ type: "RENEWAL", id: undefined }), PROD).kind, "reject");
});

Deno.test("transfer moves the best live grant and revokes the source", () => {
  const p = plan(ev({ type: "TRANSFER", transferred_from: [U1, "$RCAnonymousID:x"], transferred_to: [U2] }), PROD);
  assert(p.kind === "transfer");
  assertEquals(p.from, [U1]);
  const now = new Date(T);
  const rows = transferRows(p, [
    { active: true, expires_at: new Date(EXP).toISOString() },
    { active: false, expires_at: null },
  ], now);
  assertEquals(rows.map((r) => [r.user_id, r.active, r.expires_at]), [
    [U1, false, new Date(T).toISOString()],
    [U2, true, new Date(EXP).toISOString()],
  ]);
  // Nothing live to move: the source still loses, the destination gets nothing.
  assertEquals(transferRows(p, [], now).map((r) => r.user_id), [U1]);
});

Deno.test("transfer between non-Supabase ids is ignored", () => {
  assertEquals(plan(ev({ type: "TRANSFER", transferred_from: ["a"], transferred_to: ["b"] }), PROD).kind, "ignore");
});

function fakeStore(upsertResult = true) {
  const writes: unknown[] = [];
  const store: EntitlementStore = {
    upsert: (rows) => {
      writes.push(...rows);
      return Promise.resolve(upsertResult);
    },
    read: () => Promise.resolve([]),
  };
  return { store, writes };
}

function req(body: unknown, auth: string | null = "s3cret", method = "POST") {
  const headers = new Headers({ "Content-Type": "application/json" });
  if (auth !== null) headers.set("Authorization", auth);
  return new Request("http://local/revenuecat-webhook", {
    method,
    headers,
    body: method === "POST" ? JSON.stringify(body) : undefined,
  });
}

Deno.test("handle: refuses a wrong secret before touching storage", async () => {
  const { store, writes } = fakeStore();
  const res = await handle(req(ev({ type: "RENEWAL" }), "wrong"), {
    secret: "s3cret",
    allowedEnvironments: undefined,
    store,
    now: () => new Date(T),
  });
  assertEquals(res.status, 401);
  assertEquals(writes.length, 0);
});

Deno.test("handle: 500 when the secret is not configured", async () => {
  const { store } = fakeStore();
  const res = await handle(req(ev({ type: "RENEWAL" })), { secret: "", allowedEnvironments: undefined, store, now: () => new Date(T) });
  assertEquals(res.status, 500);
});

Deno.test("handle: writes a grant, and answers 200 for a deleted user", async () => {
  const ok = fakeStore();
  const res = await handle(req(ev({ type: "RENEWAL" })), {
    secret: "s3cret",
    allowedEnvironments: undefined,
    store: ok.store,
    now: () => new Date(T),
  });
  assertEquals(res.status, 200);
  assertEquals(ok.writes.length, 1);

  const gone = fakeStore(false);
  const res2 = await handle(req(ev({ type: "RENEWAL" })), {
    secret: "s3cret",
    allowedEnvironments: undefined,
    store: gone.store,
    now: () => new Date(T),
  });
  assertEquals(res2.status, 200);
  assertEquals((await res2.json()).ignored, "user no longer exists");
});

Deno.test("handle: unknown types are 200, non-POST is 405", async () => {
  const { store, writes } = fakeStore();
  const deps = { secret: "s3cret", allowedEnvironments: undefined, store, now: () => new Date(T) };
  assertEquals((await handle(req(ev({ type: "NEW_THING" })), deps)).status, 200);
  assertEquals((await handle(req(null, "s3cret", "GET"), deps)).status, 405);
  assertEquals(writes.length, 0);
});
