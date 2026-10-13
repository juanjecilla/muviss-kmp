plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.models)
            api(projects.feature.onboarding.api)
            implementation(projects.core.common)
            // Peers are reached through their `:api` only (ADR 0004): the nudge reads
            // the library to know whether this is the first TV show, and Settings'
            // door to turn notifications on.
            implementation(projects.feature.collection.api)
            implementation(projects.feature.settings.api)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
        }
    }
}
