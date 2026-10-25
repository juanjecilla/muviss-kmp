# Testing

What the test suites are, where they live, and how to run them. The traps that
bite while writing a test are in [CLAUDE.md](../CLAUDE.md); this page is the map.

Every epic is expected to cover its change at each layer below that it touches,
on every target it ships to — "Android-first" is a verification order, not a
scope limit.

## Running

```bash
./gradlew jvmTest                              # every KMP module's JVM tests (what CI runs)
./gradlew :feature:search:ui:jvmTest           # one module
./gradlew :app:androidApp:testDebugUnitTest    # the Android app module (widget mapper, workers)
./gradlew :app:desktopApp:test                 # the desktop host (loopback OAuth server, …)
./gradlew iosSimulatorArm64Test                # Kotlin/Native — catches what the JVM accepts and K/N rejects
./gradlew verifySqlDelightMigration            # the .sqm chain reproduces the .sq schema
./gradlew <module>:jvmTest -Precord            # re-record that module's golden images
```

Kotlin warnings are errors in **main** source (`muviss.kmp.library`, #485), on every
concrete target of the library and Compose modules. The host modules apply neither
convention and still only warn: `:app:androidApp`, `:app:desktopApp`, `:app:webApp` and
`:app:baselineprofile`. Test source still warns (`runComposeUiTest` v2, #492) and shared
metadata compilations are skipped (#493). Fix the warning, or `@Suppress` it at the
narrowest scope with a comment; `-PallowWarnings` is for a local experiment only.

After a rebase, add `--rerun-tasks`: Gradle will call a suite up to date from
before the merge, and CI's clean checkout will not.

## Layers

| Layer | Where | Notes |
|---|---|---|
| Unit | `commonTest` (or `jvmTest` for JVM-only code) in each module | `commonTest` names are `underscore_case`: Kotlin/Native rejects punctuation in backticked names, and only the iOS compile notices. |
| Migration | `core/database/src/jvmTest/.../*MigrationTest.kt` | Open the previous schema's fixture (`databases/<n>.db`), migrate, assert data survived. `verifyMigrations` checks the schema shape; these check the data. |
| Repository / integration | `:data` modules' `jvmTest` | Real SQLDelight over `JdbcSqliteDriver.IN_MEMORY` + `Schema.create`, never a mocked query. |
| Sync | `core/sync/src/jvmTest`, `app/shared/src/jvmTest/.../sync` | Against `FakeSupabaseServer` (`:core:testing`), which models PostgREST and the server triggers by hand. Keep it in step with `supabase/migrations`. |
| Server | `supabase/tests/database` (pgTAP), `supabase/functions/**/*_test.ts` (Deno) | CI's `supabase` lane runs both on any PR touching `supabase/`, against a local database-only stack (#280). |
| Server (live) | `scripts/sync/verify-*.sh` | Run against a Supabase project, never in CI — see [SYNC.md](SYNC.md). They are the only check on `FakeSupabaseServer`'s assumptions. |
| Performance | `*PerformanceBudgetTest`, `*QueryBudgetTest`, `SyncIdleBudgetTest` | Budgets are **operation counts** (queries via `CountingDriver`, requests), not wall-clock times, so they are stable on any machine. |
| UI | `:ui` modules' `jvmTest`, `runComposeUiTest` | Compose tests only execute on the JVM (Skiko ships with the desktop artifact). Test JVMs are pinned to `en-US`. |
| Screenshot | `src/jvmTest/resources/screenshots/` per module | `assertMatchesGolden(name)` inside `GoldenSurface`. Always pass `darkTheme` explicitly. Tolerance and the macOS/Linux font drift are in CLAUDE.md. |
| Graph | `app/shared/.../AppGraphTest` | Builds the real Koin graph and resolves every contract — the only test that catches a peer `:api` construction cycle. |
| Shape | `SyncChangeSetShapeTest`, `ExportShapeTest` | Pin which tables sync and which fields a backup carries, so neither changes by accident. |

## Do the blocking checks go red?

A check that cannot fail reads as protection and gives none (#486). Every CI step
that blocks a merge is listed with its evidence, taken from the last 150 failed
runs of `ci.yml` and `pr-guards.yml` on 2026-10-21 and re-checked step by step on
2026-10-25 (a failed job only proves the step that failed, not the ones it skipped).
**Add a row in the same PR as a new blocking check.** A row says one of:

- **Tested**: a test builds its red case at run time and asserts a non-zero exit
  (never a committed example that has to be kept failing).
- **Seen**: it failed on a real PR or run (linked). Weaker: it proves the check
  can fail, not that it would have caught the incident it was added for.
- **Neither**: no proof either way. Tracked in #490; until it has a fixture, treat
  a green result as "nothing noticed", not "verified".

| Check | Evidence |
|---|---|
| Actions pinned to SHAs (`pr-guards`) | Tested: `scripts/ci/test/check_action_pins_test.rb` |
| Workflows declare `permissions:` | Tested: `scripts/ci/test/check_workflow_permissions_test.rb` |
| Runner images pinned | Tested: `scripts/ci/test/check_runner_pins_test.rb` |
| Module graph (ADR 0004) | Tested: `build-logic/src/test/kotlin/ModuleGraphTest.kt` |
| R8 left a mapping and no missing rules | Tested: `scripts/ci/test/check-r8-output-test.sh` |
| Release scripts, Play listing limits, rollout ramp, staged schedule, Sunday gates, iOS train | Tested: `scripts/release/test/`, `fastlane/test/*_test.rb`; `Release scripts` and `iOS App Store metadata` also Seen ([https://github.com/juanjecilla/muviss-kmp/actions/runs/39399425196](https://github.com/juanjecilla/muviss-kmp/actions/runs/39399425196), [https://github.com/juanjecilla/muviss-kmp/actions/runs/39650983892](https://github.com/juanjecilla/muviss-kmp/actions/runs/39650983892)) |
| Spotless | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/37541711250](https://github.com/juanjecilla/muviss-kmp/actions/runs/37541711250) |
| Unit tests (JVM) | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/39558184173](https://github.com/juanjecilla/muviss-kmp/actions/runs/39558184173) |
| Coverage report (Kover) | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/38200910828](https://github.com/juanjecilla/muviss-kmp/actions/runs/38200910828) |
| Assemble Android debug APK (`assembleDebug`) | Neither |
| Kotlin/Native tests | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/39375986054](https://github.com/juanjecilla/muviss-kmp/actions/runs/39375986054) |
| iOS link (debug and release frameworks) | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/37541711250](https://github.com/juanjecilla/muviss-kmp/actions/runs/37541711250), [https://github.com/juanjecilla/muviss-kmp/actions/runs/38006059080](https://github.com/juanjecilla/muviss-kmp/actions/runs/38006059080) |
| Xcode build | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/39804969995](https://github.com/juanjecilla/muviss-kmp/actions/runs/39804969995) |
| iOS UI smoke | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/39853611208](https://github.com/juanjecilla/muviss-kmp/actions/runs/39853611208) |
| npm lockfiles current | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/39375691774](https://github.com/juanjecilla/muviss-kmp/actions/runs/39375691774) |
| Desktop compile | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/37541711250](https://github.com/juanjecilla/muviss-kmp/actions/runs/37541711250) |
| Desktop tests | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/38524760290](https://github.com/juanjecilla/muviss-kmp/actions/runs/38524760290) |
| Desktop packaged smoke launch | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/37219640556](https://github.com/juanjecilla/muviss-kmp/actions/runs/37219640556) |
| Release build with R8 (`assembleRelease`, `bundleRelease`) | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/37837578547](https://github.com/juanjecilla/muviss-kmp/actions/runs/37837578547) |
| `landing-guard` | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/39390390579](https://github.com/juanjecilla/muviss-kmp/actions/runs/39390390579) |
| `main-source-guard`, `release-notes-guard` | Seen: [https://github.com/juanjecilla/muviss-kmp/actions/runs/37538708054](https://github.com/juanjecilla/muviss-kmp/actions/runs/37538708054) |
| `changes` (lane filter fails open) | Tested: `scripts/ci/test/decide-lanes-test.sh` |
| `verifyReleaseExceptionClasses`, `verifyReleaseBundleComposeResources`, `verifyDebugApkComposeResources`, `licenseeAndroidRelease` | Neither |
| `verifyJlinkModules` | Neither (its build file records one first-run catch, `java.prefs`; nothing re-proves it) |
| `verifyMigrations` | Neither (the `*MigrationTest`s prove data survives, not that the shape check fails) |
| Detekt (stock rules) | Neither. The custom rules in `:detekt-rules` have their own tests |
| Android Lint, a11y = error | Neither |
| `supabase` lane (pgTAP, Deno) | Neither |
| Build-logic tests, metadata klibs, web compile | Neither |
| `web-tests` | Not blocking: switched off (`if: false`), see EPICS.md "Web is deferred" |

## Shared test code

`:core:testing` holds the golden harness, `CountingDriver`, `FakeSupabaseServer`
and test insets. Add a fake there once a second module needs it, rather than
copying it (EPIC 38, #71, is moving the existing copies in).

## Device-only checks

Some things only a device shows: Glance widgets (they render through the
app-widget host, not Skiko), window insets (Skiko reports zero), R8 keep rules,
and anything platform-specific in WorkManager, BGTask or the iOS widget. These
are listed on their issues as manual verification steps; when one can't be done
in the PR, it gets an issue of its own.
