plugins {
    id("muviss.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.settings.domain)
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
            // `FakeClock` (the shared `AppClock` fake).
            implementation(projects.core.testing)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
            // Test-only, for ImportViewModelTest's fakes — production code here
            // only ever depends on settings:domain's ImportActions.
            implementation(projects.feature.collection.api)
            implementation(projects.feature.progress.api)
        }
        // FileImporter.android.kt (EPIC 18) needs an activity-result launcher
        // to bridge the system document picker back into a suspend call.
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}
