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
#   scripts/gradle/update-verification.sh --prune  also drop the checksums Gradle wrote that
#                                                   no lane resolves any more
set -euo pipefail
cd "$(dirname "$0")/../.."

# --prune drops the checksums Gradle wrote and keeps the hand-verified ones (cross-host
# archives such as Binaryen's macOS x86_64, which no leg can resolve, #850). Deleting the
# whole file dropped those too. Each host's lanes then write their own again. It also drops
# the hand-verified ones for a version the pins do not list (#859), so a bump retires them.
if [ "${1:-}" = "--prune" ]; then
  python3 -I scripts/gradle/check-hand-verified.py --prune gradle/verification-metadata.xml gradle/libs.versions.toml
  python3 -I scripts/gradle/strip-generated-checksums.py gradle/verification-metadata.xml
fi

case "$(uname -s)" in
  # iosArm64 too: Xcode's build resolves the device klibs and their resource zips (#497).
  Darwin) ios_tasks=(iosSimulatorArm64Test linkReleaseFrameworkIosSimulatorArm64
    compileKotlinIosArm64 iosArm64ResolveResourcesFromDependencies linkDebugFrameworkIosArm64) ;;
  *) ios_tasks=() ;;
esac

# A lane can fail on a host for reasons that have nothing to do with dependencies (golden images and
# font rendering differ on Windows, say). The checksums of what that lane resolved are written
# regardless, and CI is what judges the tests, so the run only warns.
status=0
./gradlew \
  --init-script scripts/gradle/resolve-all.init.gradle.kts \
  --write-verification-metadata sha256 \
  -Dorg.gradle.jvmargs="-Xmx8g -Dfile.encoding=UTF-8" \
  --no-configuration-cache --continue --console=plain \
  resolveAllDependencies \
  spotlessCheck detekt \
  jvmTest :app:androidApp:testDebugUnitTest :app:desktopApp:test \
  compileKotlinJs compileKotlinWasmJs allMetadataJar \
  :app:webApp:jsBrowserDevelopmentWebpack :app:webApp:wasmJsBrowserDevelopmentWebpack \
  :app:webApp:wasmJsBrowserDistribution \
  "${ios_tasks[@]}" \
  :app:androidApp:assembleDebug :app:androidApp:lintDebug \
  :app:androidApp:assembleRelease :app:androidApp:bundleRelease \
  :app:androidApp:licenseeAndroidRelease :app:androidApp:verifyReleaseExceptionClasses \
  :app:desktopApp:packageDistributionForCurrentOS :app:desktopApp:verifyJlinkModules \
  verifySqlDelightMigration koverHtmlReport \
  kotlinNpmInstall kotlinWasmNpmInstall kotlinStoreYarnLock kotlinWasmStoreYarnLock || status=$?
[ "$status" -eq 0 ] || echo "::warning::some lanes failed on this host (exit $status); checksums were still written"

echo "gradle/verification-metadata.xml updated: $(grep -c '<component ' gradle/verification-metadata.xml) components"

# A hand-verified checksum for a version the pins do not list, or a pins file for another Kotlin
# version, fails here (#859); --prune removes the former.
python3 -I scripts/gradle/check-hand-verified.py gradle/verification-metadata.xml gradle/libs.versions.toml
