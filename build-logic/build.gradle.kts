plugins {
    `kotlin-dsl`
}

dependencies {
    // Plugin markers made available to precompiled convention scripts.
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.android.kmpLibrary.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.kotlin.composeCompiler.gradlePlugin)
}
