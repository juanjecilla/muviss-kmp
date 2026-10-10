// "Delete account" (ADR 0019): the only place the service role touches a
// user's data, and it can only ever reach the caller.
//
// The user id is never taken from the request body or a query parameter. It
// is whatever GoTrue says the caller's own access token belongs to, so the
// worst a caller can do is delete themselves. Every table that holds user
// data references `auth.users(id) on delete cascade`, so deleting the auth
// user is the whole job — rows, entitlement, co-watch rows addressed to them.

export interface Deps {
  supabaseUrl: string;
  anonKey: string;
  serviceKey: string;
  fetch: typeof fetch;
}

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** The bearer token from an `Authorization` header, or null. */
export function bearer(header: string | null): string | null {
  if (header === null) return null;
  const m = /^Bearer\s+(\S+)$/i.exec(header.trim());
  return m ? m[1] : null;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export async function handle(req: Request, deps: Deps): Promise<Response> {
  if (req.method !== "POST") return json(405, { error: "method not allowed" });
  const token = bearer(req.headers.get("Authorization"));
  if (token === null) return json(401, { error: "missing bearer token" });
  // The anon/service keys are JWTs too on legacy projects; neither names a
  // user, so GoTrue refuses them below — but say so before asking it.
  if (token === deps.anonKey || token === deps.serviceKey) return json(401, { error: "not a user token" });

  const base = deps.supabaseUrl.replace(/\/$/, "");

  // 1. Who is calling? GoTrue verifies the token's signature and expiry and
  //    that the session still exists.
  const who = await deps.fetch(`${base}/auth/v1/user`, {
    headers: { apikey: deps.anonKey, Authorization: `Bearer ${token}` },
  });
  if (!who.ok) {
    await who.body?.cancel();
    return json(401, { error: "invalid session" });
  }
  const user = await who.json() as { id?: unknown };
  if (typeof user.id !== "string" || !UUID.test(user.id)) return json(401, { error: "invalid session" });

  // 2. Delete exactly that user, hard (not GoTrue's soft delete, which would
  //    keep the row and so keep every cascade from firing).
  const del = await deps.fetch(`${base}/auth/v1/admin/users/${user.id}`, {
    method: "DELETE",
    headers: { apikey: deps.serviceKey, Authorization: `Bearer ${deps.serviceKey}`, "Content-Type": "application/json" },
    body: JSON.stringify({ should_soft_delete: false }),
  });
  if (del.ok || del.status === 404) {
    await del.body?.cancel();
    return json(200, { deleted: user.id });
  }
  console.error(`admin delete failed: HTTP ${del.status} ${await del.text()}`);
  return json(500, { error: "delete failed" });
}
