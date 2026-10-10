// RevenueCat -> public.entitlement (ADR 0019, EPIC 32 #75).
//
// RevenueCat calls this for every subscription event. It must be configured
// with an Authorization header whose value is exactly REVENUECAT_WEBHOOK_SECRET
// (RevenueCat sends the configured value verbatim). The gateway's JWT check is
// off for this function (`[functions.revenuecat-webhook] verify_jwt = false`).
//
// Env:
//   REVENUECAT_WEBHOOK_SECRET        required; the shared secret
//   REVENUECAT_ALLOWED_ENVIRONMENTS  optional; default PRODUCTION. Set
//                                    "SANDBOX,PRODUCTION" for store testing.
//   SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY  injected by the runtime
import { handle, postgrestStore } from "./handler.ts";

const store = postgrestStore(Deno.env.get("SUPABASE_URL") ?? "", Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "");

Deno.serve(async (req) => {
  try {
    return await handle(req, {
      secret: Deno.env.get("REVENUECAT_WEBHOOK_SECRET"),
      allowedEnvironments: Deno.env.get("REVENUECAT_ALLOWED_ENVIRONMENTS"),
      store,
      now: () => new Date(),
    });
  } catch (e) {
    // A storage failure: answer 5xx so RevenueCat retries the event.
    console.error(e instanceof Error ? e.message : String(e));
    return new Response(JSON.stringify({ error: "internal" }), {
      status: 500,
      headers: { "Content-Type": "application/json" },
    });
  }
});
