plugins {
    id("muviss.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.nextpick.api)
            implementation(projects.feature.nextpick.domain)
            implementation(projects.feature.progress.api)
            implementation(projects.feature.onboarding.api)
            implementation(projects.core.designsystem)
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
            implementation(projects.core.testing)
            implementation(projects.feature.progress.api)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
