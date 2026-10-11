plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.models)
            api(projects.feature.progress.api)
            implementation(projects.feature.collection.api)
            implementation(projects.core.common)
            implementation(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies {
            // `FakeClock` (the shared `AppClock` fake).
            implementation(projects.core.testing)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
        }
    }
}
