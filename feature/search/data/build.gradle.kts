plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.search.api)
            implementation(projects.feature.search.domain)
            implementation(projects.core.network)
            implementation(projects.core.common)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(projects.core.network)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
