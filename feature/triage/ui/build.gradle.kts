plugins {
    id("muviss.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.triage.domain)
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
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
            // Test-only: the deck's use cases are constructed from real
            // domain types over fakes of the peer contracts, so what the
            // ViewModel exercises is production wiring rather than a mock of it.
            implementation(projects.feature.triage.api)
            implementation(projects.feature.collection.api)
            implementation(projects.feature.progress.api)
        }
        // Golden-image capture needs Skia, so the deck's screenshot tests are
        // JVM-only. `compose.uiTest` and the Skiko desktop binary arrive from
        // the `muviss.kmp.compose` convention.
        jvmTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
