// Registers `resolveAllDependencies` on every project: it resolves every
// resolvable configuration, so Gradle sees (and, with
// `--write-verification-metadata`, records) every artifact the build can ask for,
// not only the ones one lane happens to touch (#480). Used to regenerate
// gradle/verification-metadata.xml, see scripts/gradle/update-verification.sh.
//
//   ./gradlew --init-script scripts/gradle/resolve-all.init.gradle.kts resolveAllDependencies
allprojects {
    tasks.register("resolveAllDependencies") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doLast {
            configurations
                .filter { it.isCanBeResolved }
                .forEach { config ->
                    try {
                        config.resolve()
                    } catch (e: Exception) {
                        // A configuration that cannot resolve on this host (an iOS
                        // klib on Linux, say) is not an error for this task.
                        logger.info("skipped ${project.path}:${config.name}: ${e.javaClass.simpleName}")
                    }
                }
        }
    }
}
