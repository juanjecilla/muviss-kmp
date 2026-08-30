#!/bin/bash
# Runs a real schema migration on a real device, against a seeded database.
#
# `verifyMigrations` and the jvmTest suite both prove the .sqm chain reproduces
# the .sq files and that data survives — on the JVM. What they cannot show is
# that Android's driver takes the upgrade path at all, which depends on
# user_version being read and onUpgrade firing. This ran once for real and is
# worth keeping: the failure mode is an app that launches fine on a clean
# install and crashes only for people upgrading.
#
# Destroys the app's current data. Anything not already pushed to the server is
# gone. Pass --force to acknowledge that.
#
# Usage: scripts/sync/verify-migration.sh --force [FROM_VERSION]
set -u
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/sync/lib.sh
. scripts/sync/lib.sh

[ "${1:-}" = "--force" ] || { echo "This wipes the app's data. Re-run with --force if that is fine."; exit 1; }
FROM="${2:-7}"
FIXTURES=core/database/src/commonMain/sqldelight/databases
SRC="$FIXTURES/$FROM.db"
[ -f "$SRC" ] || { echo "no fixture $SRC"; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
STAGE="$WORK/seeded.db"
cp "$SRC" "$STAGE"

# The checked-in fixtures ship user_version 0. A real install of that version
# has it set, and that is precisely what makes the driver run onUpgrade rather
# than treat the file as brand new — so seeding it is the whole point.
sqlite3 "$STAGE" "PRAGMA user_version = $FROM;"
sqlite3 "$STAGE" "INSERT INTO episodePlay(episodeId, mediaId, watchedAtEpochMs, isDirty) VALUES
  ('tmdb:tv:1399/1/1','tmdb:tv:1399',1000,0),
  ('tmdb:tv:1399/1/1','tmdb:tv:1399',2000,1),
  ('tmdb:tv:1399/1/2','tmdb:tv:1399',3000,1);" 2>/dev/null
sqlite3 "$STAGE" "INSERT OR REPLACE INTO appSettings(id, lastSyncedAtEpochMs) VALUES (0, 9999);" 2>/dev/null

echo "seeded v$FROM: $(sqlite3 "$STAGE" 'SELECT COUNT(*) FROM episodePlay;') plays"

adb_ shell am force-stop "$PKG"
adb_ shell pm clear "$PKG" >/dev/null
adb_ push "$STAGE" /data/local/tmp/muviss.db >/dev/null
adb_ shell run-as "$PKG" mkdir -p databases
adb_ shell "run-as $PKG sh -c 'cat /data/local/tmp/muviss.db > databases/muviss.db'"
adb_ shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8

AFTER=$(pull_database "$WORK/after")
echo
echo "user_version : $(sqlite3 "$AFTER" 'PRAGMA user_version;')"
echo "episodePlay  : $(sqlite3 "$AFTER" "SELECT group_concat(name||':'||type) FROM pragma_table_info('episodePlay');")"
echo "plays kept   : $(sqlite3 "$AFTER" 'SELECT COUNT(*) FROM episodePlay;')"
echo "ids          : $(sqlite3 "$AFTER" 'SELECT group_concat(id, " | ") FROM episodePlay;')"
echo "lastSyncedAt : $(sqlite3 "$AFTER" 'SELECT lastSyncedAtEpochMs FROM appSettings WHERE id=0;')  (seeded 9999 — must survive)"
echo
echo "Crashes, if any:"
adb_ logcat -d -b crash 2>&1 | grep -m3 -E "FATAL|Exception" || echo "  none"
