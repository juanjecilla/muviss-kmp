# Sync verification scripts

Sync is the one feature whose correctness lives partly on a server this repo
does not build. `verifyMigrations`, the jvmTest suites and CI all stop at the
`SyncBackend` seam — everything past it (RLS policies, the last-write-wins
trigger, whether an OAuth provider is actually enabled) is a live-project
property that no unit test can see. These scripts check the far side.

They exist because each one was written to answer a real failure, and each
failure looked like something it wasn't. That reasoning is in the header of
every script; read it before trusting the output.

## Running them

Everything runs from the repo root, against a linked project and a connected
device:

```bash
supabase link --project-ref <ref>     # once; writes supabase/.temp/project-ref
scripts/sync/sync-status.sh
```

Two things break these more often than any bug:

- **More than one device attached.** A phone plugged in to charge counts. Set
  `MUVISS_DEVICE=emulator-5554` (see `adb devices`) instead of unplugging.
- **A debuggable build.** They read the app database through `run-as`, which
  release builds refuse.

Requires `adb`, `sqlite3`, `curl`, `python3` and the `supabase` CLI.

## The scripts

| Script | Answers |
|---|---|
| `sync-status.sh` | Device rows vs server rows vs dirty rows, and the two sync timestamps. Start here. |
| `verify-rls.sh` | Can the anon key — which ships inside the app — read or write anyone's data? |
| `verify-lww-trigger.sh` | Does `discard_stale_write()` fire on the upsert path the client actually uses? |
| `diagnose-signin.sh` | Why OAuth sign-in didn't complete, walked boundary by boundary. |
| `verify-migration.sh` | Does a schema migration run on a real device against a seeded old database? **Wipes app data.** |

`lib.sh` is shared helpers; source it, don't run it.

## Two things worth knowing before reading any output

**A 200 does not mean a write landed.** `discard_stale_write()` rewrites `NEW`
back to `OLD` rather than raising, so a rejected stale push returns 2xx with
the row untouched. That is deliberate — it is what lets `SyncEngine` push
without a read-before-write round trip (ADR 0009) — but it means "the request
succeeded" is never evidence. Only re-reading the row is. `verify-lww-trigger.sh`
is built entirely around that distinction.

**An empty 200 from an unauthenticated read is correct.** `auth.uid()` is null
without a token, so `using (auth.uid() = user_id)` matches nothing and
PostgREST returns `[]`. A 200 *with rows* is the leak. `verify-rls.sh` says so
in its own output rather than leaving you to interpret a status code.

## Credentials

Several scripts read the signed-in session's access token off the device,
because exercising the server as the app does is the entire point. **None of
them print it**, and none should be changed to: a token that reaches stdout
ends up in a scrollback buffer, a screenshot, or a pasted bug report.

The anon key comes from `supabase projects api-keys`, so nothing here needs a
key checked into the repo or added to `local.properties`.

These are development tools. They are not wired into CI, and
`verify-lww-trigger.sh` writes a synthetic row to the live project (and hard-
deletes it afterwards), so don't point them at anything you would mind
touching.
