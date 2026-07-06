# Offline-first with SQLDelight as the source of truth

The user's collection and watch progress live in a local SQLDelight database that is the single source of truth. The app is fully usable with no account and no network (search/browse aside, which are inherently online). Cross-device sync is an opt-in layer added later behind a `SyncEngine` interface, not a foundational dependency.

To keep future sync cheap, every user-owned table carries `updatedAtEpochMs`, `isDirty`, and soft-delete columns from day one, so a change-log/outbox exists before any backend is chosen.

## Consequences

- No login is required to use the app.
- The chosen backend (Supabase vs Firebase) is deliberately deferred; `SyncEngine` stays vendor-agnostic (push/pull + change-log replay).
- Web (JS/Wasm) has no SQL driver yet; the collection/progress features are not implemented there this pass.
