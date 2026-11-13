// Delete the calling user's account and, by cascade, all of their server data
// (ADR 0019, EPIC 32 #75). POST with the user's own access token:
//
//   POST /functions/v1/delete-account
//   Authorization: Bearer <access token>
//
// 200 {"deleted": "<uid>"} on success; 401 without a valid user session.
// Env: SUPABASE_URL, SUPABASE_ANON_KEY, SUPABASE_SERVICE_ROLE_KEY (injected).
import { handle } from "./handler.ts";

Deno.serve(async (req) => {
  try {
    return await handle(req, {
      supabaseUrl: Deno.env.get("SUPABASE_URL") ?? "",
      anonKey: Deno.env.get("SUPABASE_ANON_KEY") ?? "",
      serviceKey: Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
      fetch,
    });
  } catch (e) {
    console.error(e instanceof Error ? e.message : String(e));
    return new Response(JSON.stringify({ error: "internal" }), {
      status: 500,
      headers: { "Content-Type": "application/json" },
    });
  }
});
