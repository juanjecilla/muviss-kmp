plugins {
    id("muviss.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.collection.domain)
            implementation(projects.core.designsystem)
            // launchReporting / CrashReportingExceptionHandler on every viewModelScope (EPIC 26).
            implementation(projects.core.common)
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
            // CollectionRefreshThrottle takes an AppClock; the tests fake it. (:core:common is
            // a commonMain dependency now too, so nothing to add here.)
        }
    }
}
