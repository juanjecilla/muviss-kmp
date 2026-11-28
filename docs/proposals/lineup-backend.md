# Hosting the Lineup on Supabase (later option)

Status: documented, not built. Today the Lineup is a static file (ADR 0040).

## When it would be worth it
Editing without a PR, many authors, per-region volume that makes one file
unwieldy, or generated shelves.

## Design
- Reuse the sync project, with content isolated in its own `content` schema:
  `content.lineup`, `content.shelf`, `content.title_set`.
- `anon` gets `select` only, through RLS. No `insert`/`update`/`delete` policy
  for `anon` or `authenticated`; writes use the dashboard or the service role.
- No foreign key, view or function joins `content` to a user table.
- An Edge Function renders the same v1 JSON, so the app does not change: point
  `lineupUrl` at it. It would be the project's first Edge Function.
- If the function is not used, `content` must be added to `[api] schemas` in
  `supabase/config.toml`, which today exposes `public` and `graphql_public`.
- The service role key never ships in the app or lands in the repo.

## Needs
1. Migration creating the schema, tables, grants and RLS.
2. pgTAP test: `anon` can read `content`, cannot write it, and still cannot read
   any user table.
3. The function, with `ETag` and `Cache-Control`.
4. Seeding from the checked-in JSON, validated by the same schema.
5. Try it on the staging project first (#151).

## Risks
- Free-tier projects pause when idle. Keep the static file published and the
  app's stored copy, so a paused project costs freshness, not Discover.
- Anonymous traffic shares the project's quota with paying sync users. Check
  limits before switching; a separate project is the way out.
- Anonymous reads add a request to a project that today only signed-in people
  reach: update `docs/PRIVACY.md`.
