// deno test supabase/functions  (see docs/SYNC.md "Entitlement")
import { assertEquals } from "jsr:@std/assert@1.0.13";
import { bearer, handle } from "./handler.ts";

const UID = "00000000-0000-0000-0000-0000000000a1";

interface Call {
  url: string;
  method: string;
  auth: string | null;
}

function fakeFetch(userStatus: number, userBody: unknown, deleteStatus = 200) {
  const calls: Call[] = [];
  const f = ((input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    const headers = new Headers(init?.headers);
    calls.push({ url, method: init?.method ?? "GET", auth: headers.get("Authorization") });
    if (url.endsWith("/auth/v1/user")) {
      return Promise.resolve(new Response(JSON.stringify(userBody), { status: userStatus }));
    }
    return Promise.resolve(new Response("{}", { status: deleteStatus }));
  }) as typeof fetch;
  return { f, calls };
}

function deps(f: typeof fetch) {
  return { supabaseUrl: "http://local/", anonKey: "anon-key", serviceKey: "service-key", fetch: f };
}

function req(auth: string | null, body: unknown = {}, method = "POST") {
  const headers = new Headers();
  if (auth !== null) headers.set("Authorization", auth);
  return new Request("http://local/delete-account", {
    method,
    headers,
    body: method === "POST" ? JSON.stringify(body) : undefined,
  });
}

Deno.test("bearer parses only a Bearer token", () => {
  assertEquals(bearer("Bearer abc"), "abc");
  assertEquals(bearer("bearer abc"), "abc");
  assertEquals(bearer("abc"), null);
  assertEquals(bearer("Bearer "), null);
  assertEquals(bearer(null), null);
});

Deno.test("deletes exactly the user the token resolves to, ignoring the body", async () => {
  const { f, calls } = fakeFetch(200, { id: UID });
  const res = await handle(req("Bearer user-jwt", { user_id: "00000000-0000-0000-0000-0000000000ff" }), deps(f));
  assertEquals(res.status, 200);
  assertEquals(calls.length, 2);
  assertEquals(calls[0], { url: "http://local/auth/v1/user", method: "GET", auth: "Bearer user-jwt" });
  assertEquals(calls[1], { url: `http://local/auth/v1/admin/users/${UID}`, method: "DELETE", auth: "Bearer service-key" });
});

Deno.test("no token, the anon key or the service key never reach the admin API", async () => {
  for (const auth of [null, "Bearer anon-key", "Bearer service-key", "Basic x"]) {
    const { f, calls } = fakeFetch(200, { id: UID });
    const res = await handle(req(auth), deps(f));
    assertEquals(res.status, 401, String(auth));
    assertEquals(calls.length, 0, String(auth));
  }
});

Deno.test("a token GoTrue refuses deletes nothing", async () => {
  const { f, calls } = fakeFetch(401, { msg: "invalid JWT" });
  const res = await handle(req("Bearer forged"), deps(f));
  assertEquals(res.status, 401);
  assertEquals(calls.length, 1);
});

Deno.test("a user payload without a uuid deletes nothing", async () => {
  const { f, calls } = fakeFetch(200, { id: "../users" });
  const res = await handle(req("Bearer x"), deps(f));
  assertEquals(res.status, 401);
  assertEquals(calls.length, 1);
});

Deno.test("an admin failure is a 500, an already-gone user is a 200", async () => {
  assertEquals((await handle(req("Bearer x"), deps(fakeFetch(200, { id: UID }, 500).f))).status, 500);
  assertEquals((await handle(req("Bearer x"), deps(fakeFetch(200, { id: UID }, 404).f))).status, 200);
});

Deno.test("only POST", async () => {
  const { f, calls } = fakeFetch(200, { id: UID });
  assertEquals((await handle(req("Bearer x", null, "GET"), deps(f))).status, 405);
  assertEquals(calls.length, 0);
});
