plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.models)
            api(libs.kotlinx.coroutinesCore)
            // The company choices are the Pick Session's enum (#583); only the names leave this module.
            implementation(projects.feature.nextpick.api)
        }
    }
}
