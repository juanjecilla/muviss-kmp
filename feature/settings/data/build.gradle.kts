plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.settings.api)
            implementation(projects.feature.settings.domain)
            implementation(projects.core.common)
            implementation(libs.koin.core)
        }
    }
}
