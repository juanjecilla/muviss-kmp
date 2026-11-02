# The Ops Bridge runs in GitHub Actions

Signals that need a human live in tools other than the issue tracker: crashes in Sentry, reviews and vitals in the Play Console and App Store Connect. The first need was Sentry crashes becoming GitHub issues (#300); Sentry's own integration does that automatically only on its Business plan.

## Decision

Connectors between project tools — the **Ops Bridge** (EPIC 48, #301) — are scheduled GitHub Actions running scripts in this repo. Each connector polls a **source**, turns what it finds into a normalised item, and writes it to a **sink**, leaving a dedup marker so every run is idempotent; a reverse mapping (issue closed → crash resolved) is optional per connector. Secrets stay in Actions, where the release workflows already keep the Sentry and Play credentials, and the connectors are reviewed and tested like any other code.

It is never called "sync": in this repo that word means `SyncEngine`, the user-library sync.

## Considered options

- **A hosted service** — the dormant `:server` (Ktor) or a Cloudflare Worker receiving webhooks. Real-time and properly two-way, but it needs hosting, uptime and a public endpoint for a project with no deployed server.
- **n8n or Zapier.** Least code, but the connectors would live outside the repo, its review and its CI.

Revisit when a connector needs sub-hourly latency or a source offers webhooks only.
