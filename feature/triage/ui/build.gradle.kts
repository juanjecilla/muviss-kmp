import org.jetbrains.compose.ComposePlugin

plugins {
    id("muviss.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

// The `compose` type-safe accessor isn't generated here (the Compose plugin is
// applied by the convention plugin, not this file's `plugins` block), so the
// dependency helpers are constructed the same way `muviss.kmp.compose` does.
val composeDeps = ComposePlugin.Dependencies(project)

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
            implementation(libs.compose.uiTest)
            // Test-only: the deck's use cases are constructed from real
            // domain types over fakes of the peer contracts, so what the
            // ViewModel exercises is production wiring rather than a mock of it.
            implementation(projects.feature.triage.api)
            implementation(projects.feature.collection.api)
            implementation(projects.feature.progress.api)
        }
        // `runComposeUiTest` renders through Skiko, which needs the native
        // binary for the host OS — it ships in the desktop artifact, not in
        // `ui-test` itself, so the JVM run is where the UI tests execute.
        jvmTest.dependencies { implementation(composeDeps.desktop.currentOs) }
    }
}
