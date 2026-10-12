plugins {
    id("muviss.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.profile.domain)
            implementation(projects.core.common)
            implementation(projects.core.designsystem)
            implementation(projects.models)
            implementation(libs.compose.material3.adaptive)
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
            // `FakeClock` (the shared `AppClock` fake).
            implementation(projects.core.testing)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
            // Test-only: builds a real ObserveProfileStatsUseCase with fakes for
            // the ViewModel test. Production code depends on these only through
            // profile:domain's use cases, never directly (mirrors collection:data's
            // test-only dependency on progress:domain/:data).
            implementation(projects.feature.collection.api)
            implementation(projects.feature.progress.api)
        }
        // Golden-image capture needs Skia, so the rewatch screen's screenshot
        // tests are JVM-only. `compose.uiTest` and the Skiko desktop binary
        // arrive from the `muviss.kmp.compose` convention.
        jvmTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
