plugins {
    id("muviss.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.models)
            api(projects.feature.settings.api)
            implementation(projects.core.common)
            implementation(projects.feature.collection.api)
            implementation(projects.feature.progress.api)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
