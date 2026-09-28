plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.cowatch.api)
            implementation(projects.feature.cowatch.domain)
            // Peers through their `:api` only (ADR 0004): the Watch Pool is
            // built from the library and, optionally, one named list.
            implementation(projects.feature.collection.api)
            implementation(projects.core.common)
            implementation(projects.core.database)
            implementation(projects.core.network)
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
        }
    }
}
