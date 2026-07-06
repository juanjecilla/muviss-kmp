plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.collection.api)
            implementation(projects.feature.collection.domain)
            implementation(projects.core.common)
            implementation(libs.koin.core)
        }
    }
}
