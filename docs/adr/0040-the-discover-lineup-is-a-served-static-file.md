# The Discover Lineup is a served static file, deployed from develop

**Status:** accepted, not built (EPIC 54).

Discover's shelves come from a **Lineup**, a versioned JSON file under
`website/public/lineup/`, served from muvissapp.com by GitHub Pages. The app finds
it through the Remote Config key `lineupUrl`, keeps the last good copy, and ships
a bundled one.

Lineup edits are PRs to `develop` and must go live without an app release. `main`
only takes release and hotfix merges (ADR 0025), and Pages publishes one whole
site, so a workflow builds the site from `main`, **overlays `lineup/` from
`develop`**, and deploys. The release-time Pages deploy must overlay it too, or a
release would roll the Lineup back. This is the one thing users get that does not
travel through the release train, which is why the file is schema-validated in CI.

Considered: a ConfigCat JSON value (size limits, no schema check, no review); a
public Supabase table (anonymous access on the sync project, pauses when idle;
kept as a later option in `docs/proposals/lineup-backend.md`, reachable by
changing `lineupUrl`); a separate repository (fast, but the schema and validator
drift from the app); shipping with releases (not backend-driven).

**Unverified:** the `github-pages` environment may restrict which branches can
deploy. Check that before building the workflow.
