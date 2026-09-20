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
            // `CountingDriver` wraps a `SqlDriver`, and it is `api` because a
            // consumer's test names the type it builds it over.
            api(libs.sqldelight.runtime)
        }
        // Golden capture needs Skia, so it is JVM-only. `api`, not
        // `implementation`: consumers write `ComposeUiTest` receivers.
        jvmMain.dependencies {
            api(composeDeps.uiTest)
            // `FakeSupabaseServer` is a Ktor `MockEngine` handler that speaks
            // the wire format, so it needs the mock engine and a JSON tree —
            // and deliberately nothing from `:core:sync`: it is the *server*,
            // and a fake that imported the client's own change types would
            // agree with the client about a wire shape by construction.
            api(libs.ktor.clientMock)
            api(libs.ktor.clientCore)
            api(libs.kotlinx.serializationJson)
            api(libs.kotlinx.coroutinesCore)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
