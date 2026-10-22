#!/usr/bin/env bash
# Regenerates gradle/verification-metadata.xml (#480) by running every lane CI
# runs, plus resolving every configuration, with checksum writing on. Gradle adds
# what it sees and keeps what it already trusted, so a version bump adds the new
# artifacts' sha256 and leaves the old entries (prune with --prune below).
#
# Needs macOS: the iOS lanes only run there, and an entry for an artifact only a
# lane we skipped would resolve is a check CI would then fail on.
#
#   scripts/gradle/update-verification.sh          add what a bump needs
#   scripts/gradle/update-verification.sh --prune  also drop entries nothing resolves any more
set -euo pipefail
cd "$(dirname "$0")/../.."

# --prune starts from nothing, so only what the lanes resolve today is trusted.
if [ "${1:-}" = "--prune" ]; then
  rm -f gradle/verification-metadata.xml
fi

./gradlew \
  --init-script scripts/gradle/resolve-all.init.gradle.kts \
  --write-verification-metadata sha256 \
  -Dorg.gradle.jvmargs="-Xmx8g -Dfile.encoding=UTF-8" \
  --no-configuration-cache --continue --console=plain \
  resolveAllDependencies \
  spotlessCheck detekt \
  jvmTest :app:androidApp:testDebugUnitTest :app:desktopApp:test \
  compileKotlinJs compileKotlinWasmJs allMetadataJar \
  iosSimulatorArm64Test linkReleaseFrameworkIosSimulatorArm64 \
  :app:androidApp:assembleDebug :app:androidApp:lintDebug \
  :app:androidApp:assembleRelease :app:androidApp:bundleRelease \
  :app:androidApp:licenseeAndroidRelease :app:androidApp:verifyReleaseExceptionClasses \
  :app:desktopApp:packageDistributionForCurrentOS :app:desktopApp:verifyJlinkModules \
  verifySqlDelightMigration koverHtmlReport \
  kotlinNpmInstall kotlinWasmNpmInstall kotlinStoreYarnLock kotlinWasmStoreYarnLock

echo "gradle/verification-metadata.xml updated: $(grep -c '<component ' gradle/verification-metadata.xml) components"
