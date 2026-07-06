import org.jetbrains.compose.ComposePlugin

// Convention for UI-bearing multiplatform modules: layers Compose Multiplatform
// on top of the base library convention.
plugins {
    id("muviss.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val compose = ComposePlugin.Dependencies(project)

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
    }
}
