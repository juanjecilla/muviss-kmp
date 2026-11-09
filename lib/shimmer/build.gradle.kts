// A standalone loading-shimmer library (#723): Compose only, no Muviss theme,
// no `:core:*`, no Compose Resources. It is written to be lifted into its own
// repository later, so it takes its colours, duration and reduced-motion flag
// as plain parameters. The Muviss tokens live in `:core:designsystem`.
plugins {
    id("muviss.kmp.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            // Not used by the library itself: pins kotlinx-coroutines to the catalog version. Compose alone resolves an older one for iOS.
            implementation(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies {
            // Pins kotlinx-coroutines to the catalog version; compose.uiTest alone pulls an older one.
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
