plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.triage.api)
            implementation(projects.feature.triage.domain)
            implementation(projects.core.common)
            implementation(projects.core.database)
            implementation(projects.core.network)
            // Peer :api only (ADR 0004) — needed here as well as in :domain
            // because this module's Koin bindings name the use-case types.
            implementation(projects.feature.collection.api)
            implementation(projects.feature.progress.api)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.sqldelight.coroutinesExtensions)
        }
        commonTest.dependencies {
            implementation(projects.core.network)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqliteDriver)
            implementation(libs.turbine)
            // Test-only: the cross-slice integration test wires collection's and
            // progress's real repositories alongside this module's against one
            // shared in-memory database, so a verdict can be asserted all the way
            // through to a *derived* WatchStatus. Production code only ever
            // depends on those features' `:api` (see commonMain above) — same
            // arrangement as collection:data's own integration test.
            implementation(projects.feature.collection.domain)
            implementation(projects.feature.collection.data)
            implementation(projects.feature.progress.domain)
            implementation(projects.feature.progress.data)
            implementation(projects.core.model)
        }
    }
}
