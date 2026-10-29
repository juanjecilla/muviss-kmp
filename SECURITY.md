# Security policy

## Reporting a vulnerability

Please report it privately through GitHub: **[Report a vulnerability](https://github.com/juanjecilla/muviss-kmp/security/advisories/new)** (the repository's *Security* tab). Don't open a public issue for it.

Include what you found, where (platform, app version from Settings → About), and how to reproduce it. You should get a first answer within a week. Fixes ship as a `hotfix/*` release (ADR 0025), and the advisory is published once a fixed version is available.

## Supported versions

Only the latest release on each store gets fixes.

## Scope

The apps (Android, iOS, desktop), the landing at muvissapp.com, and the Supabase schema and row-level-security policies under `supabase/`. Sync is not yet available in release builds, but RLS issues in the schema are in scope anyway: the anon key ships in the binary, so RLS is the whole access-control story (ADR 0022).

Out of scope: TMDB's API, and findings that need a rooted/jailbroken device or physical access to an unlocked one.

## What runs automatically

- **CodeQL** code scanning, advanced setup (`.github/workflows/codeql.yml`, #478) for Kotlin/Java, JavaScript/TypeScript and GitHub Actions workflows: it looks for injection and unsafe-deserialization patterns, and for workflow injection and risky `pull_request_target` use. It runs on pushes to `main` and `develop`, on pull requests into `develop`, `main` and `release/**` (so release and hotfix fixes are scanned during review), and weekly on `main`. A change to a local action's `action.yml` or `action.yaml` triggers it too. The Kotlin job compiles the Android, JS, Wasm and desktop targets with Gradle, so iOS-only source (`iosMain`) is not analysed by it. Findings are in the repository's *Security* tab; a dismissed one carries its reason. Default setup must stay off, because it and the advanced workflow cannot both run.
- **Dependabot alerts and security updates** are on (#479), next to the weekly version updates in `.github/dependabot.yml`.
- **Dependency review** on pull requests into `main` or `develop` (`.github/workflows/dependency-review.yml`): fails on a newly added dependency, in any scope (runtime, development or unknown), with a high-severity advisory or a GPL or AGPL licence. Weak copyleft (LGPL, MPL) passes there, since the website build uses it; the Android release has the stricter `licensee` allow-list (Apache-2.0, MIT, BSD-3-Clause) on top.
- **Secret scanning with push protection** is on.
- Every GitHub Action is pinned to a commit SHA, every runner image to a version, and every workflow declares its token permissions; CI checks all three.
