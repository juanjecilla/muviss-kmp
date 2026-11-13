// The request half of the RevenueCat webhook. Storage is injected so the
// tests can drive it without a database; `index.ts` binds it to PostgREST
// with the service role.
import { allowedEnvironments, authorized, type EntitlementRow, type HeldGrant, plan, transferRows } from "./logic.ts";

export interface EntitlementStore {
  /**
   * Upsert on `user_id`. Returns false when a row names a user that no longer
   * exists (a deleted account), which is answered 200: retrying cannot help.
   */
  upsert(rows: EntitlementRow[]): Promise<boolean>;
  read(userIds: string[]): Promise<HeldGrant[]>;
}

export interface Deps {
  secret: string | undefined;
  allowedEnvironments: string | undefined;
  store: EntitlementStore;
  now: () => Date;
}

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

export async function handle(req: Request, deps: Deps): Promise<Response> {
  if (req.method !== "POST") return json(405, { error: "method not allowed" });
  if (!deps.secret) {
    // Misconfigured, not unauthorized: a 5xx makes RevenueCat retry once the
    // secret is set, where a 401 would read as RevenueCat's mistake.
    console.error("REVENUECAT_WEBHOOK_SECRET is not set");
    return json(500, { error: "not configured" });
  }
  if (!(await authorized(req.headers.get("Authorization"), deps.secret))) {
    return json(401, { error: "unauthorized" });
  }

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "body is not JSON" });
  }

  const p = plan(body, allowedEnvironments(deps.allowedEnvironments));
  switch (p.kind) {
    case "reject":
      console.warn(`rejected: ${p.reason}`);
      return json(p.status, { error: p.reason });
    case "ignore":
      return json(200, { ignored: p.reason });
    case "write": {
      const applied = await deps.store.upsert(p.rows);
      return json(200, applied ? { applied: p.rows.length } : { ignored: "user no longer exists" });
    }
    case "transfer": {
      const held = p.from.length > 0 ? await deps.store.read(p.from) : [];
      const rows = transferRows(p, held, deps.now());
      if (rows.length === 0) return json(200, { ignored: "TRANSFER moved no known grant" });
      if (p.to.length > 0 && !rows.some((r) => r.active)) {
        // The destination gets nothing until its next RENEWAL; #281 is about
        // asking RevenueCat's REST API instead.
        console.warn(`TRANSFER ${p.sourceEvent}: no live grant on the source side to move`);
      }
      const applied = await deps.store.upsert(rows);
      return json(200, applied ? { applied: rows.length } : { ignored: "user no longer exists" });
    }
  }
}

/** PostgREST-backed store, authenticated as the service role (bypasses RLS). */
export function postgrestStore(supabaseUrl: string, serviceKey: string, fetchFn: typeof fetch = fetch): EntitlementStore {
  const base = `${supabaseUrl.replace(/\/$/, "")}/rest/v1/entitlement`;
  const headers = {
    apikey: serviceKey,
    Authorization: `Bearer ${serviceKey}`,
    "Content-Type": "application/json",
  };
  return {
    async upsert(rows) {
      const res = await fetchFn(`${base}?on_conflict=user_id`, {
        method: "POST",
        headers: { ...headers, Prefer: "resolution=merge-duplicates,return=minimal" },
        body: JSON.stringify(rows),
      });
      if (res.ok) return true;
      const text = await res.text();
      // 23503: foreign_key_violation — the auth user was deleted.
      if (res.status === 409 && text.includes("23503")) return false;
      throw new Error(`entitlement upsert failed: HTTP ${res.status} ${text}`);
    },
    async read(userIds) {
      const res = await fetchFn(`${base}?select=active,expires_at&user_id=in.(${userIds.join(",")})`, { headers });
      if (!res.ok) throw new Error(`entitlement read failed: HTTP ${res.status} ${await res.text()}`);
      return await res.json();
    },
  };
}
