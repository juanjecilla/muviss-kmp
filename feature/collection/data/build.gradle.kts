plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.collection.api)
            implementation(projects.feature.collection.domain)
            implementation(projects.feature.progress.api)
            implementation(projects.core.common)
            implementation(projects.core.database)
            implementation(projects.core.network)
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
            // Test-only: the cross-slice status-derivation integration test wires the
            // real progress repository alongside this module's own collection
            // repository against one shared in-memory database. Production code
            // only ever depends on `:feature:progress:api` (see commonMain above).
            implementation(projects.feature.progress.domain)
            implementation(projects.feature.progress.data)
        }
    }
}
