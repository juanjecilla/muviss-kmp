plugins {
    id("muviss.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.search.domain)
            implementation(projects.feature.collection.api)
            implementation(projects.feature.progress.api)
            implementation(projects.feature.triage.api)
            implementation(projects.core.designsystem)
            implementation(projects.models)
            // DetailViewModel needs AppClock: bulk marks tick aired episodes only.
            implementation(projects.core.common)
            implementation(libs.navigation.compose)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
        }
        // Golden capture needs Skia, so the Search screenshot is JVM-only.
        jvmTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
