plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.profile.api)
            implementation(projects.feature.profile.domain)
            implementation(projects.core.common)
            implementation(libs.koin.core)
        }
    }
}
