plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.models)
            api(projects.feature.collection.api)
            implementation(projects.core.common)
            implementation(projects.core.model)
            implementation(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies {
            // `FakeClock` (the shared `AppClock` fake).
            implementation(projects.core.testing)
            // Only `RefreshAndFindNewEpisodesUseCaseTest` (EPIC 5) needs a
            // suspend-test scope; `runTest` (unlike `runBlocking`) has an
            // actual for every target this module compiles for, including
            // js/wasmJs.
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
