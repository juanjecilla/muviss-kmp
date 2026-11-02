plugins {
    id("muviss.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.onboarding.api)
            // Peer :api only (ADR 0004): notifications go through Settings' one door, the
            // Own TMDB Key through its own contract.
            implementation(projects.feature.settings.api)
            implementation(projects.core.common)
            implementation(projects.core.designsystem)
            implementation(projects.models)
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
            implementation(projects.core.testing)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
        }
        // Golden capture needs Skia, so the screenshots are JVM-only.
        jvmTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
