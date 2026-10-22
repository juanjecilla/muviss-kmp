#!/usr/bin/env bash
# Regenerates gradle/verification-metadata.xml (#480) by running every lane CI
# runs, plus resolving every configuration, with checksum writing on. Gradle adds
# what it sees and keeps what it already trusted, so a version bump adds the new
# artifacts' sha256 and leaves the old entries (prune with --prune below).
#
# Run it once per OS CI builds on (macOS, Linux, Windows): some artifacts are chosen by
# the host (aapt2, Skiko's native runtime, the Kotlin/Native toolchain), so each OS adds
# its own and Gradle keeps what the others wrote. The iOS lanes run on macOS only.
# gradle-verification.yml does all three in turn.
#
#   scripts/gradle/update-verification.sh          add what a bump needs
#   scripts/gradle/update-verification.sh --prune  also drop entries nothing resolves any more
set -euo pipefail
cd "$(dirname "$0")/../.."

# --prune starts from nothing, so only what the lanes resolve today is trusted.
if [ "${1:-}" = "--prune" ]; then
  rm -f gradle/verification-metadata.xml
fi

case "$(uname -s)" in
  Darwin) ios_tasks=(iosSimulatorArm64Test linkReleaseFrameworkIosSimulatorArm64) ;;
  *) ios_tasks=() ;;
esac

./gradlew \
  --init-script scripts/gradle/resolve-all.init.gradle.kts \
  --write-verification-metadata sha256 \
  -Dorg.gradle.jvmargs="-Xmx8g -Dfile.encoding=UTF-8" \
  --no-configuration-cache --continue --console=plain \
  resolveAllDependencies \
  spotlessCheck detekt \
  jvmTest :app:androidApp:testDebugUnitTest :app:desktopApp:test \
  compileKotlinJs compileKotlinWasmJs allMetadataJar \
  "${ios_tasks[@]}" \
  :app:androidApp:assembleDebug :app:androidApp:lintDebug \
  :app:androidApp:assembleRelease :app:androidApp:bundleRelease \
  :app:androidApp:licenseeAndroidRelease :app:androidApp:verifyReleaseExceptionClasses \
  :app:desktopApp:packageDistributionForCurrentOS :app:desktopApp:verifyJlinkModules \
  verifySqlDelightMigration koverHtmlReport \
  kotlinNpmInstall kotlinWasmNpmInstall kotlinStoreYarnLock kotlinWasmStoreYarnLock

echo "gradle/verification-metadata.xml updated: $(grep -c '<component ' gradle/verification-metadata.xml) components"
