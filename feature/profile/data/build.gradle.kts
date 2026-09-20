plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.profile.api)
            implementation(projects.feature.profile.domain)
            implementation(projects.feature.collection.api)
            implementation(projects.feature.progress.api)
            implementation(projects.core.common)
            implementation(projects.core.database)
            implementation(projects.core.billing)
            implementation(projects.core.sync)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.sqldelight.coroutinesExtensions)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqliteDriver)
            implementation(projects.core.testing)
            // Test-only: the stats-aggregation integration test wires the real
            // collection and progress repositories against one shared
            // in-memory database. Production code only ever depends on their
            // `:api` (see commonMain above) — mirrors collection:data's own
            // test-only dependency on progress:domain/:data.
            implementation(projects.feature.collection.domain)
            implementation(projects.feature.collection.data)
            implementation(projects.feature.progress.domain)
            implementation(projects.feature.progress.data)
        }
    }
}
