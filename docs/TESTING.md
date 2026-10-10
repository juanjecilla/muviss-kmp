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

After a rebase, add `--rerun-tasks`: Gradle will call a suite up to date from
before the merge, and CI's clean checkout will not.

## Layers

| Layer | Where | Notes |
|---|---|---|
| Unit | `commonTest` (or `jvmTest` for JVM-only code) in each module | `commonTest` names are `underscore_case`: Kotlin/Native rejects punctuation in backticked names, and only the iOS compile notices. |
| Migration | `core/database/src/jvmTest/.../*MigrationTest.kt` | Open the previous schema's fixture (`databases/<n>.db`), migrate, assert data survived. `verifyMigrations` checks the schema shape; these check the data. |
| Repository / integration | `:data` modules' `jvmTest` | Real SQLDelight over `JdbcSqliteDriver.IN_MEMORY` + `Schema.create`, never a mocked query. |
| Sync | `core/sync/src/jvmTest`, `app/shared/src/jvmTest/.../sync` | Against `FakeSupabaseServer` (`:core:testing`), which models PostgREST and the server triggers by hand. Keep it in step with `supabase/migrations`. |
| Server (live) | `scripts/sync/verify-*.sh`, `supabase/tests/database` (pgTAP) | Run against a Supabase project, never in CI yet — see [SYNC.md](SYNC.md). They are the only check on `FakeSupabaseServer`'s assumptions. |
| Performance | `*PerformanceBudgetTest`, `*QueryBudgetTest`, `SyncIdleBudgetTest` | Budgets are **operation counts** (queries via `CountingDriver`, requests), not wall-clock times, so they are stable on any machine. |
| UI | `:ui` modules' `jvmTest`, `runComposeUiTest` | Compose tests only execute on the JVM (Skiko ships with the desktop artifact). Test JVMs are pinned to `en-US`. |
| Screenshot | `src/jvmTest/resources/screenshots/` per module | `assertMatchesGolden(name)` inside `GoldenSurface`. Always pass `darkTheme` explicitly. Tolerance and the macOS/Linux font drift are in CLAUDE.md. |
| Graph | `app/shared/.../AppGraphTest` | Builds the real Koin graph and resolves every contract — the only test that catches a peer `:api` construction cycle. |
| Shape | `SyncChangeSetShapeTest`, `ExportShapeTest` | Pin which tables sync and which fields a backup carries, so neither changes by accident. |

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
