plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.models)
            api(projects.feature.triage.api)
            implementation(projects.core.common)
            // Peers are reached through their `:api` only (ADR 0004): triage
            // saves titles via collection and writes ticks via progress.
            implementation(projects.feature.collection.api)
            implementation(projects.feature.progress.api)
        }
        commonTest.dependencies {
            // Test-only: verdicts are asserted through the *derived* status
            // (ADR 0005), never through anything triage stores itself.
            implementation(projects.core.model)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
        }
    }
}
