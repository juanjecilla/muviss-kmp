#!/usr/bin/env bash
# Proves Gradle dependency verification can go red (#480): a throwaway project,
# written here from literals, depends on one library its metadata does not list.
# The build must fail with "Dependency verification failed"; writing the metadata
# must then make the same build pass. Needs network (Maven Central) and the repo's
# Gradle wrapper; it does not touch the repo's own gradle/verification-metadata.xml.
set -uo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/gradle"

cat > "$tmp/settings.gradle.kts" <<'KTS'
rootProject.name = "verification-fixture"
KTS
cat > "$tmp/build.gradle.kts" <<'KTS'
plugins { java }
repositories { mavenCentral() }
dependencies { implementation("org.apache.commons:commons-lang3:3.14.0") }
tasks.register("resolveIt") { doLast { configurations.runtimeClasspath.get().files } }
KTS
cat > "$tmp/gradle/verification-metadata.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" version="1.3">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>false</verify-signatures>
   </configuration>
   <components/>
</verification-metadata>
XML

gradle() { "$root/gradlew" -p "$tmp" --project-cache-dir "$tmp/.gradle" --no-daemon -q "$@"; }

fail=0
if out="$(gradle resolveIt 2>&1)"; then
  echo "FAIL: a dependency with no checksum entry resolved"; fail=1
elif ! grep -q "Dependency verification failed" <<<"$out"; then
  echo "FAIL: the build failed, but not on dependency verification:"; echo "$out" | tail -15; fail=1
else
  echo "ok   an unlisted dependency fails the build"
fi

if gradle --write-verification-metadata sha256 resolveIt >/dev/null 2>&1 && gradle resolveIt >/dev/null 2>&1 \
   && grep -q 'name="commons-lang3"' "$tmp/gradle/verification-metadata.xml"; then
  echo "ok   writing the metadata records the checksum and the build passes"
else
  echo "FAIL: writing the metadata did not make the build pass"; fail=1
fi
exit $fail
