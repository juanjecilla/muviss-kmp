plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.nextpick.api)
            implementation(projects.feature.nextpick.domain)
            implementation(projects.core.common)
            implementation(projects.core.database)
            implementation(projects.core.network)
            implementation(projects.models)
            // Peer :api only (ADR 0004): the engine's sources read collection, triage and settings through them.
            implementation(projects.feature.collection.api)
            implementation(projects.feature.progress.api)
            implementation(projects.feature.settings.api)
            implementation(projects.feature.triage.api)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.sqldelight.coroutinesExtensions)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
