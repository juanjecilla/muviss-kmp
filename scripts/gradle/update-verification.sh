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
  # iosArm64 too: Xcode's build resolves the device klibs and their resource zips (#497).
  Darwin) ios_tasks=(iosSimulatorArm64Test linkReleaseFrameworkIosSimulatorArm64
    compileKotlinIosArm64 iosArm64ResolveResourcesFromDependencies linkDebugFrameworkIosArm64) ;;
  *) ios_tasks=() ;;
esac

# A lane can fail on a host for reasons that have nothing to do with dependencies (golden images and
# font rendering differ on Windows, say). The checksums of what that lane resolved are written
# regardless, and CI is what judges the tests. So a failed *test* task only warns. Any other failed
# task (a compile error, a Gradle validation error, packaging) is a real build break: it is named
# in an ::error:: annotation and fails the leg once the checksums are written (#880).
classify_failures() { # <log> -> prints "test <task>" or "build <task>" per failed task
  awk '{ sub(/^\033\[[0-9;]*m/, ""); if ($1 == ">" && $2 == "Task" && $NF == "FAILED") print $3 }' "$1" | sort -u |
    while read -r task; do
      case "${task##*:}" in
        jvmTest | test | testDebugUnitTest | *Test) echo "test $task" ;;
        *) echo "build $task" ;;
      esac
    done
}

log="${UPDATE_VERIFICATION_CLASSIFY_ONLY:-$(mktemp)}"
status=0
if [ -z "${UPDATE_VERIFICATION_CLASSIFY_ONLY:-}" ]; then
  set +e
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
    "${ios_tasks[@]}" \
    :app:androidApp:assembleDebug :app:androidApp:lintDebug \
    :app:androidApp:assembleRelease :app:androidApp:bundleRelease \
    :app:androidApp:licenseeAndroidRelease :app:androidApp:verifyReleaseExceptionClasses \
    :app:desktopApp:packageDistributionForCurrentOS :app:desktopApp:verifyJlinkModules \
    verifySqlDelightMigration koverHtmlReport \
    kotlinNpmInstall kotlinWasmNpmInstall kotlinStoreYarnLock kotlinWasmStoreYarnLock 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  set -e
fi

failures="$(classify_failures "$log")"
build_failed="$(grep '^build ' <<<"$failures" | cut -d' ' -f2- | paste -sd, - || true)"
test_failed="$(grep '^test ' <<<"$failures" | cut -d' ' -f2- | paste -sd, - || true)"
[ -z "$test_failed" ] || echo "::warning::test tasks failed on this host (checksums still written): $test_failed"
if [ -n "$build_failed" ]; then
  echo "::error::build tasks failed on this host: $build_failed"
  [ -z "${UPDATE_VERIFICATION_CLASSIFY_ONLY:-}" ] || exit 1
  failed_build=1
elif [ "$status" -ne 0 ] && [ -z "$test_failed" ]; then
  echo "::error::gradle exited $status and no failed task was named in its output (a configuration or validation error); see the log"
  failed_build=1
fi

echo "gradle/verification-metadata.xml updated: $(grep -c '<component ' gradle/verification-metadata.xml) components"
[ -z "${failed_build:-}" ] || exit 1
