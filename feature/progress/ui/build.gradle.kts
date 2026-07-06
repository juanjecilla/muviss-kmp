plugins {
    id("muviss.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.progress.domain)
            implementation(projects.core.designsystem)
            implementation(libs.navigation.compose)
            implementation(libs.kotlinx.serializationJson)
        }
    }
}
