plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.onboarding.api)
            implementation(projects.feature.onboarding.domain)
            implementation(projects.core.common)
            implementation(projects.core.database)
            implementation(projects.feature.collection.api)
            implementation(projects.feature.settings.api)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.sqldelight.coroutinesExtensions)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqliteDriver)
            implementation(projects.core.testing)
            implementation(libs.turbine)
        }
    }
}
