plugins {
    id("muviss.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.coil.compose)
            api(libs.coil.networkKtor)
            // MetadataError -> translated copy (ErrorText.kt, EPIC 31).
            implementation(projects.models)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
