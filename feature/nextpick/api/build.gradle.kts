plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.models)
            api(libs.kotlinx.coroutinesCore)
        }
    }
}
