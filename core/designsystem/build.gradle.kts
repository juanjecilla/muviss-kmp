plugins {
    id("muviss.kmp.compose")
}

compose.resources {
    packageOfResClass = "com.codingpit.muviss.core.designsystem.generated.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.coil.compose)
            api(libs.coil.networkKtor)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
