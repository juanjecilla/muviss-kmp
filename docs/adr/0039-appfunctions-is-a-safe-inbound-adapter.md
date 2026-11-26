# AppFunctions is an inbound adapter in the Android app, with a safe surface

Android's AppFunctions (`androidx.appfunctions` 1.0.0-beta01, EPIC #701) let a system agent that holds
`EXECUTE_APP_FUNCTIONS` call Muviss without opening the app. We expose a small, fixed set of functions and
nothing else.

## Decision

- **Placement.** The adapter lives only in `:app:androidApp`, package `com.codingpit.muviss.appfunctions`. It is
  Android-only, so no dependency, KSP processor or code goes into `commonMain` (the same trap as RevenueCat).
  It delegates to the feature `:api` contracts and to `MetadataProviderRegistry`, never to `:domain` (ADR 0004).
- **Surface.** Nine functions: reads `getWatchNext`, `searchTitles`, `getLibrary`, `getTitleProgress`; writes
  `tickEpisode`, `addToLibrary`, `setFavorite`, `rateTitle`, `markMovieWatched`. Anything destructive (remove,
  clear progress or plays, delete a list, erase, restore), lists, notes, co-watch, sync, the Own TMDB Key and the
  triage verdicts stay out. Adding a function is a new decision, not a tweak.
- **Writes are silent and forward-only.** An agent tick calls `ProgressApi.setEpisodeSeen(id, true)` and writes
  no completion prompt, the same as the home-screen widget. There is no un-tick: `setEpisodeSeen(…, false)` clears
  an episode's history, and `setMovieWatched` is only called with `true`. Nothing bulk-ticks (`markPreviousSeen`
  would tick unaired episodes and break `WatchProgress`'s invariant).
- **A write on a title needs the title in the library first.** `tickEpisode`, `setFavorite`, `rateTitle` and
  `markMovieWatched` fail with a not-found error for a title that is not saved, so an agent cannot leave progress the
  user cannot see (ADR 0037). `addToLibrary` is the only way in.
- **Tick validation.** A tick needs a TV episode in the stored catalog that has aired by the person's local day.
  Unknown or unaired episodes are rejected, so `seenEpisodes` cannot pass `airedEpisodes`.
- **Errors.** Each function body runs through one guard. AppFunction exceptions pass through; a `MetadataError`
  maps to a fixed text; any other failure is recorded and answered with fixed text. No exception message reaches the
  agent, because Ktor messages can carry the request URL and a TMDB key.
- **Personal data.** The functions hand library data to whichever agent the user has chosen, and that agent may
  process it on a server. `docs/PRIVACY.md` says so.
- **Gating.** The functions are on by default in the manifest. The platform only lets callers with
  `EXECUTE_APP_FUNCTIONS` reach them, and that is a private preview today.

## Why not the alternatives

- *A broader surface (deletes, lists, sync).* An agent acting on destructive or sync-bound data is not a change to
  make quietly. Each one needs its own decision.
- *Silent ticks replaced by the Detail tick path.* The Detail path raises the snapshot's aired count and can
  produce a Completion. Keeping the widget path keeps the agent's writes as small as the widget's; the aired-count
  gap is tracked as #1115.
- *Searching through `SearchApi`.* It is an empty interface. Reaching `MetadataProviderRegistry` directly keeps the
  adapter one hop from the provider it needs.

## Consequences

- The manifest, `app_metadata.xml` and the R8 release build are checked only against the library's shipped resources
  and a build. The device check on Android 17 is #1114, not something this ADR claims is verified.
- `aggregateAppFunctions` stays set because beta01 needs it. Moving to a later release drops that argument.
