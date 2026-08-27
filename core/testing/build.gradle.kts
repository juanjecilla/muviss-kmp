@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import org.jetbrains.compose.ComposePlugin

plugins {
    id("muviss.kmp.compose")
}

// The `compose` type-safe accessor isn't generated here (the Compose plugin is
// applied by the convention plugin, not this file's `plugins` block), so the
// dependency helpers are constructed the same way `muviss.kmp.compose` does.
val composeDeps = ComposePlugin.Dependencies(project)

kotlin {
    sourceSets {
        commonMain.dependencies {
            // A testing module: assertions are part of its public surface.
            api(kotlin("test"))
        }
        // Golden capture needs Skia, so it is JVM-only. `api`, not
        // `implementation`: consumers write `ComposeUiTest` receivers.
        jvmMain.dependencies {
            api(composeDeps.uiTest)
        }
    }
}
