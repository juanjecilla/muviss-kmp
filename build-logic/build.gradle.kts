plugins {
    `kotlin-dsl`
}

dependencies {
    // Plugin markers made available to precompiled convention scripts.
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.android.kmpLibrary.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.kotlin.composeCompiler.gradlePlugin)

    // MuvissVersion's versionCode formula (ADR 0029) is pure Kotlin; these run
    // without git: `./gradlew -p build-logic test`.
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
