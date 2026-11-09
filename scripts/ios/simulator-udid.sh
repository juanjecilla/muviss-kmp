#!/bin/bash
# Prints the UDID of an available iPhone simulator on the newest installed iOS
# runtime, for `xcodebuild test -destination "platform=iOS Simulator,id=…"`.
#
# A device *name* is not stable: GitHub's macOS images drop older models (the
# UI smoke step pinned "iPhone 16" and failed once the runner only had
# "iPhone 16e" and "iPhone 17"), and a developer's Mac has whatever Xcode made.
set -euo pipefail
xcrun simctl list devices available -j | python3 -c '
import json, re, sys
devices = json.load(sys.stdin)["devices"]
def version(runtime):
    return [int(n) for n in re.findall(r"\d+", runtime.rsplit("iOS", 1)[-1])]
for runtime in sorted((r for r in devices if ".iOS-" in r), key=version, reverse=True):
    for device in devices[runtime]:
        if device["name"].startswith("iPhone"):
            print(device["udid"])
            sys.exit(0)
sys.exit("no available iPhone simulator; install an iOS runtime in Xcode")
'
