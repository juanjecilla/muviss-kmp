// Pure half of the RevenueCat webhook (ADR 0019): authorization and the
// mapping from a RevenueCat event to `public.entitlement` rows. No I/O here,
// so `deno test` covers every decision without a database or a network.
//
// Payload reference: RevenueCat "Event Types and Fields"
// (https://www.revenuecat.com/docs/integrations/webhooks/event-types-and-fields).

/** The RevenueCat entitlement identifier that unlocks sync and co-watch. */
export const SYNC_ENTITLEMENT_ID = "sync";

/**
 * Rendered by the access token hook for an active row with no end date; a
 * row written here with `expires_at: null` and `active: true` means exactly
 * that (a promotional or lifetime grant).
 */
export interface EntitlementRow {
  user_id: string;
  active: boolean;
  expires_at: string | null;
  source_event: string;
  source_event_at: string;
}

export type Plan =
  | { kind: "ignore"; reason: string }
  | { kind: "reject"; status: number; reason: string }
  | { kind: "write"; rows: EntitlementRow[] }
  | {
    kind: "transfer";
    from: string[];
    to: string[];
    sourceEvent: string;
    sourceEventAt: string;
  };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID.test(value);
}

/**
 * Events that (re)grant the entitlement up to `expiration_at_ms`. A null
 * expiration on one of these is a grant with no end date.
 */
const GRANTS = new Set([
  "INITIAL_PURCHASE",
  "RENEWAL",
  "UNCANCELLATION",
  "NON_RENEWING_PURCHASE",
  "PRODUCT_CHANGE",
  "SUBSCRIPTION_EXTENDED",
  "TEMPORARY_ENTITLEMENT_GRANT",
  "REFUND_REVERSED",
]);

/**
 * Events that do NOT end access by themselves but may move its end:
 * a cancellation keeps access until the period ends (and a refund sets
 * `expiration_at_ms` to when it took effect), a billing issue's expiration is
 * the end of the store's grace period. A null expiration on one of these is
 * read as "ends now" — never as a grant with no end date.
 */
const RESCHEDULES = new Set(["CANCELLATION", "BILLING_ISSUE"]);

/** RevenueCat's own word that access is over. */
const REVOCATIONS = new Set(["EXPIRATION"]);

/**
 * SHA-256 both sides and compare the digests byte by byte without an early
 * exit, so neither the content nor the length of the secret leaks through
 * response timing.
 */
export async function authorized(
  header: string | null,
  secret: string | undefined,
): Promise<boolean> {
  if (!secret || header === null) return false;
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(header)),
    crypto.subtle.digest("SHA-256", enc.encode(secret)),
  ]);
  const x = new Uint8Array(a);
  const y = new Uint8Array(b);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

/** `REVENUECAT_ALLOWED_ENVIRONMENTS`, comma-separated; PRODUCTION if unset. */
export function allowedEnvironments(raw: string | undefined): Set<string> {
  const list = (raw ?? "PRODUCTION")
    .split(",")
    .map((s) => s.trim().toUpperCase())
    .filter((s) => s.length > 0);
  return new Set(list.length > 0 ? list : ["PRODUCTION"]);
}

function iso(ms: number): string {
  return new Date(ms).toISOString();
}

function concernsSync(event: Record<string, unknown>): boolean {
  const ids = event.entitlement_ids;
  if (Array.isArray(ids)) return ids.includes(SYNC_ENTITLEMENT_ID);
  // `entitlement_id` is deprecated but still sent; only consulted when the
  // array is absent.
  return event.entitlement_id === SYNC_ENTITLEMENT_ID;
}

/**
 * Decide what one webhook body does to `public.entitlement`.
 *
 * - `reject` for a body that is not a RevenueCat event, or whose
 *   `app_user_id` is not a Supabase auth uid (the client must configure
 *   RevenueCat with `appUserID = auth.uid()` before purchasing, ADR 0019).
 *   A 4xx makes RevenueCat retry and shows in its dashboard, which is the
 *   point: such an event is a client bug, not a no-op.
 * - `ignore` (answered 200) for event types that do not change access, for
 *   other entitlements, and for environments not allowed here.
 */
export function plan(body: unknown, allowedEnvs: Set<string>): Plan {
  if (typeof body !== "object" || body === null) {
    return { kind: "reject", status: 400, reason: "body is not a JSON object" };
  }
  const event = (body as Record<string, unknown>).event;
  if (typeof event !== "object" || event === null) {
    return { kind: "reject", status: 400, reason: "no event" };
  }
  const e = event as Record<string, unknown>;
  const type = e.type;
  if (typeof type !== "string") {
    return { kind: "reject", status: 400, reason: "no event.type" };
  }
  if (type === "TEST") return { kind: "ignore", reason: "TEST event" };

  const environment = typeof e.environment === "string" ? e.environment.toUpperCase() : "";
  if (!allowedEnvs.has(environment)) {
    return { kind: "ignore", reason: `environment ${environment || "(none)"} not accepted` };
  }

  const id = typeof e.id === "string" ? e.id : "";
  const at = typeof e.event_timestamp_ms === "number" ? e.event_timestamp_ms : NaN;
  if (!id || !Number.isFinite(at)) {
    return { kind: "reject", status: 400, reason: "no event.id or event.event_timestamp_ms" };
  }
  const sourceEvent = `${type}:${id}`;
  const sourceEventAt = iso(at);

  if (type === "TRANSFER") {
    const uuids = (v: unknown) => (Array.isArray(v) ? v.filter(isUuid) : []);
    const from = uuids(e.transferred_from);
    const to = uuids(e.transferred_to);
    if (from.length === 0 && to.length === 0) {
      return { kind: "ignore", reason: "TRANSFER between no Supabase users" };
    }
    return { kind: "transfer", from, to, sourceEvent, sourceEventAt };
  }

  const isGrant = GRANTS.has(type);
  const isReschedule = RESCHEDULES.has(type);
  const isRevocation = REVOCATIONS.has(type);
  if (!isGrant && !isReschedule && !isRevocation) {
    return { kind: "ignore", reason: `${type} does not change access` };
  }
  if (!concernsSync(e)) {
    return { kind: "ignore", reason: `${type} is not for entitlement ${SYNC_ENTITLEMENT_ID}` };
  }

  const userId = e.app_user_id;
  if (!isUuid(userId)) {
    return { kind: "reject", status: 400, reason: "app_user_id is not a Supabase user id" };
  }

  const expMs = typeof e.expiration_at_ms === "number" ? e.expiration_at_ms : null;
  let active: boolean;
  let expiresAt: string | null;
  if (isRevocation) {
    active = false;
    expiresAt = expMs === null ? sourceEventAt : iso(expMs);
  } else if (isReschedule) {
    active = expMs !== null;
    expiresAt = expMs === null ? sourceEventAt : iso(expMs);
  } else {
    active = true;
    expiresAt = expMs === null ? null : iso(expMs);
  }

  return {
    kind: "write",
    rows: [{ user_id: userId, active, expires_at: expiresAt, source_event: sourceEvent, source_event_at: sourceEventAt }],
  };
}

/** What a TRANSFER source currently holds, as read back from the table. */
export interface HeldGrant {
  active: boolean;
  expires_at: string | null;
}

/**
 * A TRANSFER moves the store purchase from one App User ID to another; its
 * body does not reliably say what the purchase grants, so the destination
 * takes the best grant any source held (no end date beats any date, a later
 * date beats an earlier one) and every source loses its own.
 */
export function transferRows(
  p: Extract<Plan, { kind: "transfer" }>,
  held: HeldGrant[],
  now: Date,
): EntitlementRow[] {
  const live = held.filter((h) => h.active && (h.expires_at === null || new Date(h.expires_at) > now));
  let best: HeldGrant | null = null;
  for (const h of live) {
    if (best === null) best = h;
    else if (best.expires_at !== null && (h.expires_at === null || new Date(h.expires_at) > new Date(best.expires_at))) {
      best = h;
    }
  }
  const rows: EntitlementRow[] = p.from
    .filter((u) => !p.to.includes(u))
    .map((u) => ({
      user_id: u,
      active: false,
      expires_at: p.sourceEventAt,
      source_event: p.sourceEvent,
      source_event_at: p.sourceEventAt,
    }));
  if (best !== null) {
    for (const u of p.to) {
      rows.push({
        user_id: u,
        active: true,
        expires_at: best.expires_at,
        source_event: p.sourceEvent,
        source_event_at: p.sourceEventAt,
      });
    }
  }
  return rows;
}
