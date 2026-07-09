plugins {
    id("muviss.kmp.compose")
}

kotlin {
    // iOS framework consumed by the Xcode app.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.designsystem)
            implementation(projects.core.network)
            implementation(projects.core.database)

            // Feature UI (screens + nav sections)
            implementation(projects.feature.search.ui)
            implementation(projects.feature.collection.ui)
            implementation(projects.feature.progress.ui)
            implementation(projects.feature.profile.ui)
            implementation(projects.feature.settings.ui)

            // Feature data (Koin modules)
            implementation(projects.feature.search.data)
            implementation(projects.feature.collection.data)
            implementation(projects.feature.progress.data)
            implementation(projects.feature.profile.data)
            implementation(projects.feature.settings.data)

            implementation(libs.navigation.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)
            implementation(libs.coil.compose)
            implementation(libs.coil.networkKtor)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.compose.uiToolingPreview)
        }
    }
}
