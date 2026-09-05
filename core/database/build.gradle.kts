plugins {
    id("muviss.kmp.library")
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("MuvissDatabase") {
            packageName.set("com.codingpit.muviss.core.database")

            // Migration baseline (EPIC 10): the schema grew additively and
            // migration-less through EPICs 0-8 — every install so far has
            // always created the database fresh from the current .sq files,
            // there was never an upgrade path. AppSettings.sq /
            // CollectionEntry.sq / EpisodeProgress.sq / Profile.sq as they
            // stand today *are* schema version 1; no `.sqm` file is needed
            // to represent it. From here on, any change to a `CREATE TABLE`
            // (new column, renamed column, new table that existing installs
            // must pick up without losing data) must ship as a new
            // `<version>.sqm` file alongside the `.sq` change. NOTE the
            // number in the filename is the version it migrates FROM, not
            // to (confirmed by EPIC 15, the first real migration here) — the
            // migration off baseline version 1 is `1.sqm`, not `2.sqm`, and
            // it produces the `2.db` fixture in `schemaOutputDirectory`
            // below (regenerate via `generateCommonMainMuvissDatabaseSchema`
            // after any schema change). `verifyMigrations` below fails the
            // build if the `.sqm` chain doesn't reproduce the schema the
            // `.sq` files declare, so a missing migration is caught at
            // compile time rather than at some user's upgrade.
            verifyMigrations.set(true)
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))

            // EPIC 13 (web productionization, ADR 0008's amendment): flipped
            // on so `web-worker-driver` can back real web persistence. Every
            // generated mutation (`INSERT`/`UPDATE`/`DELETE`) becomes a
            // `suspend fun` on every platform, not just web — Android/iOS/JVM
            // keep working unchanged because their sync drivers still return
            // `QueryResult.Value` (see `MuvissDatabase.Schema.synchronous()`
            // used by their `DatabaseDriverFactory` actuals). Select queries
            // (`Query<T>.executeAsList()` etc.) are untouched by this flag —
            // they stay driver-shaped, so sync platforms keep using them
            // as-is and only the web driver needs the `awaitAsList()`-style
            // calls from `async-extensions`.
            generateAsync.set(true)
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.models)
            api(projects.core.common)
            api(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutinesExtensions)
            implementation(libs.sqldelight.asyncExtensions)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.androidDriver)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqliteDriver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.nativeDriver)
        }
        // Web persistence (EPIC 13 / ADR 0008's amendment):
        // `web-worker-driver` talks to a Web Worker running
        // `@cashapp/sqldelight-sqljs-worker` (SQL.js compiled to wasm), which
        // is why the schema needed `generateAsync` in the first place.
        //
        // The klib halves are declared on `webMain` — the shared js+wasmJs
        // source set the default hierarchy template creates, holding
        // `SchemaEnsuringDriver.kt` — rather than twice on `jsMain` and
        // `wasmJsMain`. `webMain` has a compilation of its own,
        // `compileWebMainKotlinMetadata`, which type-checks its sources ahead
        // of and independently of either target and resolves against
        // `webMain`'s dependencies only: declaring these two per target left
        // that compilation with nothing, so `./gradlew build` failed with
        // `Unresolved reference 'WebWorkerDriver'` while CI's
        // `compileKotlinJs`/`compileKotlinWasmJs` stayed green (CI runs
        // `allMetadataJar` now too). Both targets inherit what is declared
        // here.
        //
        // `matching { }.configureEach { }` rather than `val webMain by
        // getting`, because the template creates `webMain` *after* this block
        // runs — `getting` throws `KotlinSourceSet with name 'webMain' not
        // found` at configuration time, while `configureEach` also applies to
        // elements added later.
        //
        // `npm(...)` stays per target below: it feeds each target's own
        // webpack/yarn resolution and means nothing to a metadata compilation.
        matching { it.name == "webMain" }.configureEach {
            dependencies {
                implementation(libs.sqldelight.webWorkerDriver)
                implementation(libs.kotlinx.browser)
            }
        }
        // `muviss-sqljs-worker` is a local package (src/webWorker), not a
        // published one: it is our fork of `@cashapp/sqldelight-sqljs-worker`,
        // which has no persistence seam at all — `new SQL.Database()` and no
        // `export()` — so durable storage could not be reached by configuring
        // it (ADR 0008, EPIC 24 amendment). It has to be a *package* rather
        // than a loose file in resources so that `new URL("muviss-sqljs-worker
        // /muviss-sqljs.worker.js", import.meta.url)` resolves through
        // node_modules and webpack bundles the worker's own `import
        // initSqlJs from "sql.js"` with it. A file referenced by path is
        // copied verbatim instead, leaving a bare specifier the browser cannot
        // resolve — that is snag 1 in ADR 0008, and it presents as a worker
        // that loads with a 200 and then hangs forever with no console error.
        //
        // `sql.js` stays declared here as well as in the local package.json:
        // this is what puts it in the target's own yarn resolution.
        jsMain.dependencies {
            implementation(npm("sql.js", "1.10.3"))
            implementation(npm("muviss-sqljs-worker", File(rootDir, "core/database/src/webWorker")))
        }
        wasmJsMain.dependencies {
            implementation(npm("sql.js", "1.10.3"))
            implementation(npm("muviss-sqljs-worker", File(rootDir, "core/database/src/webWorker")))
        }
    }
}
