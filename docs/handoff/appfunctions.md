# Handoff · Add Android AppFunctions to Muviss

Written 2026-11-02 for the agent that implements this. All repo facts below were checked against
`origin/develop` at `c75e4945` (**checked 2026-11-02**). Every external reference carries the date we checked it;
re-check anything older than a week before relying on it: AppFunctions is moving fast.

## 1. Goal and scope

Let a system agent (an assistant that holds `EXECUTE_APP_FUNCTIONS`) read and update the user's Muviss library **without opening
the app**: ask "what do I watch next?", tick the episode, add a title, rate it.

**Decisions already taken by the owner (2026-11-02):**

| Question | Decision |
| --- | --- |
| What is this? | A **real product feature**. Normal flow: `feat/…` from `develop`, PR, CodeRabbit, squash. Not a talk demo. |
| Which functions? | **Reads + safe mutations** (section 5). **No** destructive functions, no lists, no notes, no co-watch, no sync, nothing that touches the Own TMDB Key. |
| Does an agent tick emit a Completion? | **No. Silent, like the widget**: call `ProgressApi.setEpisodeSeen(id, true)`, not `tickEpisode`. Revisit later if the owner wants the "What's next?" prompt after an agent tick. |

**Non-goals:** destructive actions (`CollectionApi.remove`, `clearPlays`, `clearProgress`, `setEpisodeSeen(false)`, `deleteList`,
erase/import/restore), `ListsApi`, notes, favorites of other users, a triage-verdict function (`TriageApi` has no write method), iOS / desktop / web.

## 2. Read first, and the rules that bind this work

Read in this order: `CLAUDE.md`, `AGENTS.md`, `CONTEXT.md` (the glossary: use its words), `docs/TESTING.md`, ADRs 0004 (slices), 0025
(git flow), 0034 (backup), `docs/EPICS.md` (headings). Line numbers drift, so rules are quoted by section.

- **Baseline.** Branch from a fresh `origin/develop`: `git fetch origin develop && git switch -c feat/appfunctions origin/develop`.
  The main checkout at `~/Projects/CodingPit/Muviss` is a detached HEAD far behind develop: do not use it. Use a worktree.
- **Git flow** (AGENTS.md § "Branches, PRs and merging"): PR against `develop`, title as a conventional commit
  (`feat(appfunctions): …`), squash merge. Wait for `gh pr checks --watch`, then CodeRabbit: answer every thread, `@coderabbitai resolve`,
  `gh pr merge --squash`. "Never bypass a red check."
- **Landing** (AGENTS.md § "Every feature reaches the landing"): a free, user-visible feature needs `website/src/i18n/en.ts` + `es.ts` and the Play
  listing, or the PR carries the **`no-landing`** label (CI `landing-guard`). **Recommendation: `no-landing`.** Only privileged agents can call these
  functions today and Gemini integration is a private preview (docs, checked 2026-11-02), so landing copy would promise something users cannot use.
  Open an issue for the copy ("announce assistant integration on the landing once it is generally available"). Owner confirms.
- **Release notes** (same section): a `feat` with no user-visible surface must end its commit body with a **`Notes: none`** trailer. Recommended here too.
  Keep the trailer in the squash body: `gh pr merge --squash --body "<body>"`.
- **Claim the ADR when the branch opens** (AGENTS.md § "Claim ADR and schema numbers"): **0036 and 0037 are already taken** (Display Preferences and
  Muted Genres sync; a tick adds to the library), and others may have landed since. List `docs/adr/` on a fresh `origin/develop` and take the next free
  number (called **<ADR>** below). Write it into `docs/EPICS.md` on the branch and push at once, and update every `<ADR>` reference in this handoff's
  deliverables. No schema change is expected, so no `.sqm`: re-check that against the current schema version.
- **Undone work becomes an issue** (AGENTS.md § "Undone work gets an issue"): anything you could not verify ("compiles but never ran on a device") must be a
  GitHub issue before the session ends.
- **Before you finish** (AGENTS.md § "Before you finish"): `./gradlew spotlessCheck detekt`; `./gradlew build allMetadataJar` (use the heap workaround from AGENTS.md
  if it dies with `Java heap space`, issue #49); run it: `./gradlew :app:androidApp:installDebug`; re-run tests with `--rerun-tasks` after a rebase.
- **Errors** (CLAUDE.md, rule "Network errors are `MetadataError`, never exception text"): never forward `e.message` to a caller, a state or a report: Ktor messages embed
  the request URL, which can carry the TMDB key. AppFunction exceptions get **fixed text**.
- **Custom detekt rules** (`detekt-rules/`): `ForbiddenViewModelScopeLaunch`, `HardcodedUiCopy` (only `commonMain`). `UnusedPrivateMember` is active.
  Spotless uses ktlint 1.8.0. Lint a11y findings are errors and never go into `lint-baseline.xml`.
- **`verifyModuleGraph`** (in `check`) enforces ADR 0004 across project dependencies; `config/module-graph-exceptions.txt` is empty on purpose.
- **Licences:** `verifyOssLicenses` is in `check`. New dependencies need `./gradlew regenerateOssLicenses` and licensee must accept them (Apache-2.0 expected).

## 3. Platform facts (all checked 2026-11-02)

| Fact | Detail | Source (checked 2026-11-02) |
| --- | --- | --- |
| Library | `androidx.appfunctions:appfunctions` and `…:appfunctions-compiler` **`1.0.0-beta01`** (released 2026-10-21) | https://developer.android.com/jetpack/androidx/releases/appfunctions · Google Maven metadata |
| Renames vs older docs | `@AppFunction` → **`@AppFunctionDeclaration`** (alpha13); `isDescribedByKDoc` has **no default** (beta01); `appfn:description` → **`appfn:instructions`** in `app_metadata.xml` (beta01); `AppFunctionName` now in `androidx.appfunctions`; `AppFunctionConfiguration`/`AppFunctionContext` removed (alpha12) | same release notes |
| Docs lag | The developer docs page was "Last updated 2026-10-22" yet its snippets still use `@AppFunction`/alpha10. **Trust the skill and the samples over the docs page** | https://developer.android.com/ai/appfunctions · https://developer.android.com/ai/appfunctions/add-appfunctions |
| Status | "Experimental … subject to change"; "As of May 2026, AppFunctions integration with Gemini is in a private preview with trusted testers"; only a limited number of apps and system agents can reach the full pipeline | https://developer.android.com/ai/appfunctions |
| Who can call | Callers need `android.permission.EXECUTE_APP_FUNCTIONS` | same page |
| Platform | Android 16 (API 36)+. Library AAR **minSdk 24** (= Muviss `minSdk 24`: no `tools:overrideLibrary`), **no `.so` files** (16 KB pages: no issue), ships `proguard.txt` consumer rules | AAR inspected from Google Maven |
| Official skill | `device-ai/appfunctions` in https://github.com/android/skills, `last-updated: 2026-10-28`, targets beta01, has an "Upgrade to 1.0.0-beta01" guide. Prerequisites in the skill: `targetSdk 36`+, `compileSdk 37`+ (Muviss already has 36 / 37) | repo read 2026-11-02 |
| Samples | https://github.com/android/appfunctions (`ChatApp`, `agent/` Testing Agent), pinned to `beta01`, last commit 2026-10-30 | repo read 2026-11-02 |
| New transitive deps | guava 32.0.1-android, `androidx.appsearch` 1.1.0, kotlinx-coroutines-guava 1.9.0, kotlin-stdlib 2.2.20 (older than the app's 2.4.21: Gradle resolves up) | beta01 POM |

**Muviss toolchain on develop** (checked 2026-11-02): AGP 9.2.1 with **built-in Kotlin** (no `kotlin-android` plugin), Kotlin 2.4.21, Compose Multiplatform 1.12.2, Koin 4.2.2,
Gradle wrapper **9.8.2** (CLAUDE.md still mentions 9.4: stale), `compileSdk 37 / targetSdk 36 / minSdk 24`, Java 11 source level, daemon JDK Corretto 21.
**KSP is not used anywhere in the repo yet.**

## 4. Where it goes and how to wire it

### 4.1 Placement
- Everything lives in **`:app:androidApp`**, package `com.codingpit.muviss.appfunctions`. AppFunctions are Android-only: never put the dependency, KSP or the code in a
  `commonMain` (same trap as RevenueCat, CLAUDE.md "RevenueCat is not wired up yet": an Android-only artifact in `commonMain` breaks `compileKotlinWasmJs` for every downstream module).
- Delegate **only** to `:api` interfaces (`ProgressApi`, `CollectionApi`) and to `MetadataProviderRegistry` (`:core:network`). Never to `:domain` (ADR 0004; `verifyModuleGraph`).
  `:app:androidApp` already depends on `feature:{collection,settings,progress}:api`, `core:network`, `models`.
- DI: Koin is started in `MuvissApplication.onCreate` (guarded by `GlobalContext.getOrNull() == null`) **before any Android component**, so a cold-started service can resolve dependencies. Follow
  the existing precedent: `class TickAction : ActionCallback, KoinComponent { private val progressApi: ProgressApi by inject() … }`
  (`app/androidApp/src/main/kotlin/com/codingpit/muviss/widget/WidgetActions.kt`) and `NewEpisodesWorker`. The skill's framework-agnostic pattern (no Hilt) fits.
- Keep pure mapping/validation logic (string id → `MediaId`, API model → serializable DTO, rating range) in its own file so it is unit-testable in `src/test` like `WatchNextWidgetState`.

### 4.2 Sketch of the entry point (not compiled: adapt and build it)
```kotlin
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "MuvissAppFunctionService",
    appFunctionXmlFileName = "muviss_app_function_service",
)
abstract class BaseMuvissAppFunctionService : AppFunctionService(), KoinComponent {
    private val progressApi: ProgressApi by inject()
    private val collectionApi: CollectionApi by inject()
    private val providers: MetadataProviderRegistry by inject()

    /** … KDoc written for the model (section 5) … */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getWatchNext(): List<WatchNextEntry> = withContext(Dispatchers.IO) {
        progressApi.observeWatchNext().first().map { it.toEntry() }
    }
}
```
KSP generates the concrete `MuvissAppFunctionService` and `assets/muviss_app_function_service.xml`. The `functionId` the system uses is built from the **abstract Base class**
(`com.codingpit.muviss.appfunctions.BaseMuvissAppFunctionService#getWatchNext`), not the generated one.

### 4.3 Build wiring checklist
1. `gradle/libs.versions.toml`: add `ksp` (plugin) and `appfunctions = "1.0.0-beta01"` with `androidx-appfunctions` and `androidx-appfunctions-compiler`. Comment why they are Android-only.
2. `app/androidApp/build.gradle.kts`: apply the KSP plugin, `implementation(libs.androidx.appfunctions)`, `ksp(libs.androidx.appfunctions.compiler)`, and
   `ksp { arg("appfunctions:aggregateAppFunctions", "true") }`.
   **Open technical risk: pick a KSP version that works with Kotlin 2.4.21 + AGP 9.2.1 built-in Kotlin, the configuration cache and the build cache.** Evidence from our demo (private repo
   `juanjecilla/DevfestOurense26`): KSP **2.3.12** with Kotlin 2.4.20 and AGP 9.1.1 built-in Kotlin built and ran fine (alpha10), but that is not this exact combination.
3. `AndroidManifest.xml`: a `<service>` named after the **generated** class, `android:exported="true"`, `android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"`,
   `tools:targetApi="36"`, properties `android.app.appfunctions.schema` (`app_functions_schema.xsd`) and `android.app.appfunctions.v2` (`muviss_app_function_service.xml`),
   intent filter action `android.app.appfunctions.AppFunctionService`; and an app-level `<property android:name="android.app.appfunctions.app_metadata" android:resource="@xml/app_metadata" />`.
4. `res/xml/app_metadata.xml`: `appfn:instructions` with **Operational Patterns** and **Constraints** (free text), and `appfn:displayDescription`.
5. R8: the system binds the service **by name**. Repo lesson (CLAUDE.md, `proguard-rules.pro`): every class the system instantiates by name has an explicit `-keep`, and "a quiet R8 is not a
   working R8; re-test on a device". Add a `-keep` for the service and the entry point **and test a minified release build on a device**, even though the AAR ships consumer rules.
6. `./gradlew regenerateOssLicenses` then `verifyOssLicenses`; adjust licensee only if a licence is not on the allow-list.

## 5. The function surface

Names are verbs, ids are strings, results are small. Use the glossary: **WatchNext** (never "up next"/"queue"), **Tick** (a verb, not "check"/"mark" as a noun), **CollectionEntry**.

| Function | Kind | Delegates to | Notes |
| --- | --- | --- | --- |
| `getWatchNext()` | read | `ProgressApi.observeWatchNext().first()` | title, `mediaId`, next episode `SxEy` + name, seen/aired counts. CLAUDE.md: "what do I watch next" has one implementation, do not rebuild the join |
| `searchTitles(query, type?)` | read | `MetadataProviderRegistry.require(SourceId.TMDB).search(...)` | needs network + TMDB credential (Own key → remote → built-in). Map `MetadataError` (below) |
| `getLibrary(status?, favoritesOnly?)` | read | `CollectionApi.observeSummaries().first()` | filter on `WatchStatus` (NotStarted = watchlist). Returns rating: personal data, return only what the question needs |
| `getTitleProgress(mediaId)` | read | `ProgressApi.observeSeenEpisodes`, `storedSeasons` | |
| `tickEpisode(episodeId)` | mutation | `ProgressApi.setEpisodeSeen(id, true)` | **silent**, same as the widget's `TickAction`. **Validate before writing**: `setSeen` does not check the catalog, so require the episode to exist in the catalog and to have aired (`EpisodeOrdering.airedBy`), else throw `AppFunctionElementNotFoundException` / `AppFunctionInvalidArgumentException`. An unaired tick would push `seenEpisodes` past `airedEpisodes` and `WatchProgress`'s `require` would crash Library. Tests: unknown episode id and unaired episode id must not raise the seen count. Only ever `seen = true`: `setEpisodeSeen(…, false)` clears the episode's history outright, so there is **no un-tick function** |
| `addToLibrary(mediaId)` | mutation | `providers.forId(id).details(id)` then `CollectionApi.add(details)` | a TV detail costs `1 + ceil(seasons/20)` requests; idempotent |
| `setFavorite(mediaId, favorite)` | mutation | `CollectionApi.setFavorite` | **membership check first**: the update affects no rows for a title not in the library and returns normally, so the agent would report success. Throw `AppFunctionElementNotFoundException` when the title is not saved |
| `rateTitle(mediaId, rating)` | mutation | `CollectionApi.setRating(mediaId, rating)` | validate `1..10` first (it throws `IllegalArgumentException`); same **membership check** as `setFavorite` (`AppFunctionElementNotFoundException` when not saved); note the API takes `Int?` where `null` clears the rating: do **not** expose clearing |
| `markMovieWatched(mediaId)` | mutation | `ProgressApi.setMovieWatched(id, true)` | silent; `true` only |

**Hard rules**
- Ids are strings: `tmdb:tv:1399`, episodes `tmdb:tv:1399/1/2`. Parse with `MediaId.parse` / `EpisodeId.parse` (`models/…/MediaId.kt`, `Media.kt`) and map **every** failure to `AppFunctionInvalidArgumentException` with fixed text: `MediaId.parse` throws `IllegalStateException` for an unknown type (`tmdb:game:1399`), not `IllegalArgumentException`, and a well-formed id with an unregistered source makes `providers.forId` throw. Validate type and source at the boundary; catch both exception types.
- **Never** use `markPreviousSeen` or any bulk tick for "catch up": ticking unaired episodes breaks `WatchProgress.require(seenEpisodes <= airedEpisodes)` and the Library crashes (CLAUDE.md).
- **`tickEpisode` missing catalog:** `storedSeasons` returns null, or an empty list, for a title whose episodes were never stored (the `episode` cache has no backfill, ADR 0015, so an upgraded install has none until the app runs once). Offline the tool cannot prove the episode exists. Recovery: fetch that one title's seasons with `EpisodeCatalogSource.fetch` and store them with `EpisodeCatalogStore.save`, then validate. If the fetch fails (`MetadataError.Offline`, `RateLimited`…), write nothing and throw a recoverable error with fixed text ("Open this show in Muviss while online so its episodes load, then try again"). Offline validation still runs whenever a stored catalog exists. Tests: empty catalog offline returns the recoverable error and changes no row; empty catalog online validates and ticks.
- **`tickEpisode` stale aired count:** the snapshot's `collectionEntry.airedEpisodes` is set at the last refresh, so an episode that aired since can pass validation while the snapshot is still below the new seen count, and Library's `require` throws. The tick and the reconciliation run in **one transaction**: after the write, run `raiseAiredToSeen` for the title (the query a sync pull already runs, `CollectionEntry.sq`), so the snapshot is never below the seen count. A title not in the library has no row to raise. Test: an episode that aired after the last snapshot refresh ticks without throwing, and Library derives the title's status afterwards.
- All writes are idempotent. Writes mark rows dirty; if automatic sync is on, `SyncCoordinator` pushes them later (safe).
- Exceptions: use `androidx.appfunctions` types (`AppFunctionInvalidArgumentException`, `AppFunctionElementNotFoundException`, `AppFunctionLimitExceededException`, …). Map `MetadataError` (`RateLimited`, `Unauthorized`,
  `OwnKeyRejected`, `NotFound`, `Offline`, `Unknown`) to fixed, agent-readable text with a recovery hint ("try again later", "the user must fix the TMDB key in Settings"). Never `e.message`.
- Prefer not to take `LocalDate`/`LocalTime` inputs. alpha10 could not deserialize `java.time` inputs at all (`Unable to deserialize class java.time.LocalTime`, verified on a device 2026-10-11). On **beta01 a `LocalTime`
  input works only as an object with all four fields** `{"hour": 14, "minute": 30, "second": 0, "nanoOfSecond": 0}`; the string `"14:30"` and a partial object are rejected with code 1001 (tested on a Pixel 9 Pro, Android 17,
  2026-11-02, scratch function). An agent filling that object is more error-prone than one string, so for dates use an epoch day or an ISO string you parse and validate yourself. Release notes (checked 2026-11-02): alpha13 added
  `java.time` support in `AppFunctionData`, beta01 fixed desugaring apps (Muviss uses desugaring? check before relying on it). Beware `todayEpochDay` semantics: the in-flight branch `fix/656-local-day` changes the local-day rules.
- Agents need no login: every function above works signed-out and offline except `searchTitles` and `addToLibrary` (network). Sync and co-watch are paid and gated off in release builds: out of scope.
- Run all work on `Dispatchers.IO`; AppFunction implementations run on the main thread by default (docs, checked 2026-11-02).

**KDoc is the interface** (the skill's rules, checked 2026-11-02): start with an imperative verb and the user benefit; state call order with the standard phrase
`Required workflow: Call "searchTitles" first to obtain a valid mediaId.`; `@param` tags on functions; **inline KDoc on every property of an `@AppFunctionSerializable`** (class-level `@param` is ignored by KSP);
descriptive, not imperative; no fluff. The global `appfn:instructions` carries cross-function patterns ("resolve a title with `searchTitles` before `addToLibrary`") and constraints ("ratings are 1 to 10"), not per-function descriptions.

## 6. How the skill was applied once (worked example)

Private repo `juanjecilla/DevfestOurense26` (checked 2026-11-02; ask the owner for access): branches `01-base-agenda` → `02-appfunctions-skill` → `03-final`, tags `cp-*`. Branch `02` has the skill installed under
`.claude/skills/appfunctions/` and one commit per skill step (discovery → implementation → KDoc refinement → adb script): copy that flow. Gotchas found there:
- `adb shell cmd app_function …` **does not exist on the Android 16 GA image** (service present, no shell command). It works on Android 17 / API 37.x (checked on a Pixel 9 Pro, 2026-10-11).
- The index document type is `AppFunctionStaticMetadata-<package>`; the service registers a few seconds after boot ("Can't find service: app_function" if asked too early).
- Error codes seen over adb: 1001 invalid argument, 1002 function disabled, 1500 element not found, 1501 limit exceeded.
- Force-stopping the app and calling a function wakes the process: nothing to do on our side except Koin being up (it is).
- Official upgrade steps to beta01 (skill reference `migrate-to-service-entry-point.md`, section "Upgrade to 1.0.0-beta01", checked 2026-11-02) apply if you copy older snippets.

## 7. Test plan

1. **Unit tests** (`./gradlew :app:androidApp:testDebugUnitTest`): id parsing, rating range, error mapping (`MetadataError` → exception text, no `e.message`), DTO mapping. Evaluate the `appfunctions-testing` artifact
   (`AppFunctionTestRule`; beta01 added support for `@AppFunctionServiceEntryPoint`, release notes checked 2026-11-02).
2. **Device checks.** Needs a device with the shell command: **Android 17 / API 37.x** (physical Pixel preferred). Emulator caveat (observed 2026-10-11, emulator 37.2.12 on macOS arm64): the API 37.0 image needs
   `-qemu -enable-hvf` and its `surfaceflinger` crash-loops with `hasReadColorBufferDma`; prefer a real device. Adapt the demo's `scripts/appfunctions-adb.sh` (`check`, `list`, execute each function, error paths,
   `stop` + call, `disable`/`enabled`/`reset`). `adb shell cmd app_function help` lists `list-app-functions [--package]`, `execute-app-function … --brief-yaml`, `set-enabled`, `is-enabled`.
3. **Debug and minified release** builds both on a device (R8, see 4.3.5). Check the widget refreshes after an agent tick.
4. If no device is available: do not claim it works. Open an issue "AppFunctions compiled but never ran on a device" (AGENTS.md).

## 8. Acceptance criteria

- [ ] `feat/appfunctions` branched from fresh `origin/develop`; ADR **<ADR>** (next free number on fresh `origin/develop`) claimed in `docs/EPICS.md` and pushed when the branch opened; ADR written.
- [ ] Nine functions of section 5, no more; none destructive; ids as strings; fixed-text errors; no `e.message`.
- [ ] `docs/PRIVACY.md` updated in the implementation PR, before release: it says nobody else can see the collection, favorites or progress, and these functions hand that data to an assistant that may process it remotely.
- [ ] Code only in `:app:androidApp`; `verifyModuleGraph` green; no `commonMain` change.
- [ ] KSP + `appfunctions 1.0.0-beta01` wired; `aggregateAppFunctions` set; manifest, `app_metadata.xml` (`appfn:instructions`), R8 `-keep` in place.
- [ ] `./gradlew spotlessCheck detekt build allMetadataJar` green; `regenerateOssLicenses` committed; lint clean.
- [ ] Unit tests added; function list and execute verified on an Android 17 device (debug **and** minified release), or an issue says it was not.
- [ ] PR titled `feat(appfunctions): …` with `Notes: none` trailer and the `no-landing` label (if the owner agrees); issue opened for the landing copy.

## 9. Risks and traps

- **Stale baseline.** The main checkout is detached and ~369 files behind develop. Use a worktree from `origin/develop`.
- **Overlapping branches/worktrees** (checked 2026-11-02): `feat/widget-tick-feedback` (widget code), `fix/645-erase-signs-out`, `fix/656-local-day`, `feat/sync-entitlement-server`. Rebase before opening the PR and expect conflicts in the widget and progress areas.
- **Do not copy** the talk worktrees' `applicationIdSuffix` (`.a11ytalk`, `.a11ybroken`) into the product branch: an assistant would see a different package name.
- **Personal data.** Ratings and play history reach a third-party agent: return only what the question needs; never expose notes, the Own TMDB Key or anything from sync/co-watch. `docs/PRIVACY.md` must be updated before release (section 8): it currently says nobody else can see the collection, favorites or progress, and an agent that reads them may process them remotely.
- **Docs vs skill drift.** If the docs and the skill disagree, the skill and samples were newer on 2026-11-02. Re-check the release notes before starting; if a newer version exists, follow its upgrade section.
- **Gemini is a private preview.** The functions can be called by `adb` and by privileged test agents today; end-to-end with Gemini needs the Early Access Program. Do not promise it in user-facing copy.

## 10. Open questions for the owner (with recommendations)

1. Landing / Play listing: **`no-landing`** + `Notes: none` + an issue for later copy (recommended), or write en/es copy now?
2. ADR: **yes, next free number** ("AppFunctions are an inbound adapter in the Android app; safe surface only; agent ticks are silent").
3. Search access: use `MetadataProviderRegistry` directly from the adapter (recommended; `SearchApi` is an empty interface), or widen `SearchApi` first?
4. ~~Tracking issue~~ opened 2026-11-02: https://github.com/juanjecilla/muviss-kmp/issues/701 (labels `enhancement`, `epic`). When the branch opens, add it to `docs/EPICS.md` with the ADR number and link both ways (AGENTS.md).

## References (all checked 2026-11-02)

| Reference | URL |
| --- | --- |
| AppFunctions overview and FAQ | https://developer.android.com/ai/appfunctions |
| Add AppFunctions (snippets lag: alpha10) | https://developer.android.com/ai/appfunctions/add-appfunctions |
| Jetpack release notes (beta01, alpha13…) | https://developer.android.com/jetpack/androidx/releases/appfunctions |
| Official agent skill (beta01) | https://github.com/android/skills/tree/main/device-ai/appfunctions |
| Samples and Testing Agent | https://github.com/android/appfunctions |
| Worked example (private) | https://github.com/juanjecilla/DevfestOurense26 |
| Muviss baseline | `origin/develop` @ `c75e4945` |
