plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.progress.api)
            implementation(projects.feature.progress.domain)
            implementation(projects.core.common)
            implementation(libs.koin.core)
        }
    }
}
