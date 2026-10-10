# Security policy

## Reporting a vulnerability

Please report it privately through GitHub: **[Report a vulnerability](https://github.com/juanjecilla/muviss-kmp/security/advisories/new)** (the repository's *Security* tab). Don't open a public issue for it.

Include what you found, where (platform, app version from Settings → About), and how to reproduce it. You should get a first answer within a week. Fixes ship as a `hotfix/*` release (ADR 0025), and the advisory is published once a fixed version is available.

## Supported versions

Only the latest release on each store gets fixes.

## Scope

The apps (Android, iOS, desktop), the landing at muvissapp.com, and the Supabase schema and row-level-security policies under `supabase/`. Sync is not yet available in release builds, but RLS issues in the schema are in scope anyway: the anon key ships in the binary, so RLS is the whole access-control story (ADR 0022).

Out of scope: TMDB's API, and findings that need a rooted/jailbroken device or physical access to an unlocked one.
