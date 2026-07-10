plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.models)
            api(projects.feature.settings.api)
            implementation(projects.core.common)
            implementation(libs.kotlinx.coroutinesCore)
        }
    }
}
