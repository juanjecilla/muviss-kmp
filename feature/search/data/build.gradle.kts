plugins {
    id("muviss.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.search.api)
            implementation(projects.feature.search.domain)
            implementation(projects.core.network)
            // AppSettingsSearchOnboarding reads appSettings (EPIC 30, #73).
            implementation(projects.core.database)
            implementation(libs.sqldelight.coroutinesExtensions)
            implementation(projects.core.common)
            implementation(libs.koin.core)
            // The "For you" copy is stored as JSON (#785).
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(projects.core.network)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            // `FakeClock` and `inMemoryDatabase()`.
            implementation(projects.core.testing)
            implementation(libs.sqldelight.sqliteDriver)
        }
    }
}
