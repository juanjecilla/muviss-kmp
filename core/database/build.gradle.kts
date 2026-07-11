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
    }
}
