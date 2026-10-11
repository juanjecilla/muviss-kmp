plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.models)
            api(projects.feature.cowatch.api)
            implementation(projects.core.common)
            // Peers are reached through their `:api` only (ADR 0004): the Watch
            // Pool is built from the library, and nothing here writes progress —
            // co-watch never touches another user's source of truth (ADR 0022).
            implementation(projects.feature.collection.api)
        }
        commonTest.dependencies {
            // `FakeClock` (the shared `AppClock` fake).
            implementation(projects.core.testing)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
        }
    }
}
