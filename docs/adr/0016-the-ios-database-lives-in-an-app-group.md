# The iOS database lives in an App Group

`DatabaseFactory.ios.kt` built `NativeSqliteDriver(schema, "muviss.db")` with no configuration, which puts the file in the app's own `NSDocumentDirectory`. That was correct for as long as the app was the only thing reading it.

A WidgetKit extension is not the app. It is a **separate process with its own sandbox**, and `NSDocumentDirectory` inside it resolves to the *extension's* Documents directory — a different, empty folder. A widget reading from there would show an empty library forever, and would go on doing so no matter how much the person watched.

Worse, EPIC 22's widget writes: WidgetKit's interactive buttons run an `AppIntent` whose `perform()` executes in the extension's process. So the requirement is not "let another process read the database" but "let two processes share one".

## Decision

**`muviss.db` moves into the `group.com.codingpit.muviss` App Group container**, the only path both processes resolve to the same file. Existing installs are **moved once, on first open after upgrading**.

**The move takes SQLite's `-wal` and `-shm` sidecars with it.** Leaving them behind would strand the most recent committed transactions in a WAL file the relocated database no longer sees — which a person reads as "the last few episodes I ticked are gone".

**The move is skipped whenever the destination already exists.** A half-finished move must never overwrite the database the app has been writing to since.

**Without the entitlement, the app falls back to the old Documents path.** The App Group needs a real Apple Developer team, and `Configuration/Config.xcconfig` ships with `TEAM_ID` blank — so this is the state of every checkout until someone does the manual steps in `docs/RELEASING.md`. A missing container yields no widget and a working app, rather than a crash on launch.

**Two processes writing one SQLite file is made safe by WAL**, which `NativeSqliteDriver` enables by default.

## Considered options

- **The app writes a widget snapshot file** into the App Group — a small serialized "next up" payload the extension only reads, with ticks queued back to the app. No SQLite in the extension, no relocation, no multi-process concern. Rejected: it invents a second store with its own staleness, and a queued tick is a write that can silently fail or be replayed into a duplicate `episodePlay` row. One source of truth is the point of ADR 0002.
- **Extension reads the shared database, tick launches the app.** No multi-process *writes*. Rejected because the tap then leaves the home screen, which is most of what a widget is for; it is the read-only iOS variant this epic explicitly did not choose.
- **Copy rather than move.** Superficially safer — the old file stays as a fallback. Rejected: two files would exist and diverge, and any code still resolving the old path would read a library frozen at upgrade time. A stale copy of someone's watch history is a worse failure than a missing one.
- **Move inside a Kotlin `.sqm` migration.** Wrong layer entirely: SQLDelight migrations run *inside* an open database and cannot relocate the file they are running against.
- **Give the extension its own database and sync the two.** Rejected on sight: replication between two processes on one device, to avoid moving a file.

## Consequences

- **Every existing iOS install relocates its database on the first launch after upgrading**, silently and once. This is the riskiest single step in EPIC 22 and the reason the fallback exists.
- **`MUVISS_APP_GROUP` is a published constant** in `:core:database` and must match the entitlement on both Xcode targets. Three places now spell out `group.com.codingpit.muviss` — the constant, `iosApp.entitlements`, `MuvissWidget.entitlements` — plus the widget's `UserDefaults` suite name, which shares the container for the Undo marker.
- **Signing gained a manual prerequisite.** The App Group must be registered in the Apple Developer portal and enabled on both bundle ids, and `TEAM_ID` filled in, before either target will sign. Documented in `docs/RELEASING.md`; nothing about it can be automated from this repo.
- **The `Shared` framework is now linked by two binaries.** It is a static framework (`isStatic = true`), so the extension links rather than embeds it, and the widget bridge (`IosWidgetBridge`) is the only new API exported to Swift — deliberately flat strings, so the extension never sees `MediaId`, `EpisodeId` or a `Flow`.
- **The extension starts its own Koin graph.** No `AppDelegate` runs there, so `IosAppStartup.start()` never does; `IosWidgetBridge.ensureStarted()` is the extension's equivalent.
- **Android needed none of this.** A Glance receiver runs in the app's own process and resolves the existing global Koin graph, exactly as `NewEpisodesWorker` does. The asymmetry is entirely iOS's process model.
