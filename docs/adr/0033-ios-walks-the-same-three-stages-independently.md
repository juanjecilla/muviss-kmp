# iOS walks the same three stages, independently

ADR 0030 gave every version three weeks on Play: internal, open beta, production. It left iOS out ("iOS (TestFlight internal → external → App Store) ... this ADR does not cover them"). This ADR puts iOS on the same weekly train. It does not change Play's stages, and it does not change the shared gates.

Apple's rules below were checked against Apple's own pages on 2026-10-17. Where Apple is silent, the ADR says so and states the assumption it runs on. The implementer (WS5) must check those assumptions against a live App Store Connect account before relying on them.

## Decision

**iOS has the same three stages as Play, one week apart, on the same RC binary.**

| When | iOS stage | What happens |
|---|---|---|
| Monday 23:00 UTC (week 0) | internal | `release-rc.yml`'s `ios-rc` job uploads the RC to TestFlight and distributes it to the internal group. This is automatic. |
| Sunday (week 0) | open beta | The same RC goes to a TestFlight **external** group that has a public link, and is submitted to Beta App Review. |
| Sunday (week 1) | production | The App Store version is submitted with *automatic release on approval* and a *7-day phased release*. The submitted build is the RC from week 0. |

- **The RC is never rebuilt for iOS either.** The App Store build is the RC uploaded on Monday. Apple's TestFlight builds are held for 90 days, which covers the thirteen days between cut and submit (an assumption: see below).
- **Each platform has its own Stage** in the release issue's marker. A version can be on beta for iOS and on production for Play on the same Sunday, and the train records each separately.
- **Gates are split.** Some gates hold both platforms; others hold one.
  - *Shared* (a hold on both): the `release-blocker` label, an open PR into `release/X.Y.Z`, CI on the branch head not being green, the `DRAFT:` marker in the store notes, and a head that is not the newest RC.
  - *Per platform*: the Sentry crash gate, the store's own outcome (a Beta App Review rejection, an App Review rejection, a missing secret), and the Apple-specific rules below.
- **Platforms advance independently.** An iOS hold never blocks Play. A Play hold does block the App Store production step (see the next rule), because the App Store version has to come from a `main` that has the release merged.
- **The App Store production step needs `release/X.Y.Z` merged into `main`.** The merge is what makes `release.yml` tag the version, so the iOS submit follows the Play production step. If Play is held, the iOS production step waits with it. The only way around that hold is the explicit `workflow_dispatch` the owner runs for iOS, which performs the merge itself and submits. (The hold and the override are decisions of this ADR; the workflow shape is WS5's.)
- **The Sentry gate filters by platform, not by release name.** Both apps report the same release, `com.codingpit.muviss@<versionName>+<versionCode>` (`MuvissCrashReporting.releaseOf`), and since ADR 0030 derives `versionCode` from the version, the iOS and Play builds of a version share it. So `sentry_gate.rb`'s query adds `os.name:iOS` or `os.name:Android` to `firstRelease:"…"`; without it each platform's gate would hold on the other's crashes. One release name is kept on purpose: a version stays one Sentry release, with both platforms side by side.
- **Phased release is automatic; so is resuming the gate's own pause.** Apple rolls the version out over seven days to a random sample of users with automatic updates on. The daily job (`app-store-phased-ramp.yml`, WS5) reads the Sentry gate and pauses the phased release through the App Store Connect API when the gate fails, and resumes a pause *it* made once the gate passes again on a later daily run. A manual `app-store-brake.yml` dispatch pauses too, for a person; only the owner resumes a brake pause. The job tells the two apart by recording the pauses it makes on the release issue.

## Apple's rules, checked

**(a) One unreleased version at a time.** Apple's submission FAQ says: "Each platform can have one app version submission under review at a time," and "Submissions may not be reviewed in the order you submit them." The App Store Connect status list has no "superseded" status. A version in *Waiting for Review* or *In Review* can be removed, and it then becomes *Developer Rejected*; a resubmitted version "will start over". A version in *Pending Developer Release* can also be removed. Sources: [Overview of submitting for review](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/overview-of-submitting-for-review), [App and submission statuses](https://developer.apple.com/help/app-store-connect/reference/app-information/app-and-submission-statuses), [Remove a submission from review](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/remove-a-submission-from-review).

*Rule when N-1 is stuck and N is due.* The train never waits more than one week for a stuck version:

- **N-1 in *Waiting for Review* or *In Review*:** N-1 is removed from review and marked *superseded* on its release issue; N is submitted in its place. Its fixes are already in N, because ADR 0030 forwards fixes into newer release branches. N-1 is not resubmitted.
- **N-1 *Rejected* by App Review:** N-1 is superseded when N is due, and the rejection reason is copied onto N's release issue. N must answer it (a rejection the RC cannot answer is a `release-blocker`). If N cannot be submitted, iOS holds with the reason and N-1 stays rejected; iOS skips a week, Play does not.
- **N-1 in *Pending Developer Release*:** this is not expected, because the release is automatic. If it happens, iOS holds N, and the hold names N-1. N is not submitted until N-1 is live. Submitting N could end N-1's rollout (see (b)), so an approved build is never superseded without an owner's decision.
- **N-1 in a phased release:** not stuck. N is submitted on schedule.

**(b) A new release ends the previous phased rollout.** Apple's phased-release page says nothing about this. It says only that a phased release "will stop" when the app is removed from sale, and that it "can be paused for up to 30 days" in total. The owner's premise is that a newer release ends the older rollout. That is the working assumption here, and it is not verified.

The consequence is arithmetic, not a bug. If N-1 is approved at `A` and N is approved at `A'`, N-1's seven days end at `A + 7`, and N's release ends it at `A'`. With N submitted seven days after N-1, `A' − A = 7 + r − r'`, where `r` is N's review time and `r'` is N-1's. So the seven-day ramp is cut short by `r' − r`: it is cut when N-1 sat in review longer than N did, and not otherwise. With review times of a day or two, the cut is usually a day or less. The ramp is not guaranteed to finish, and the release issue says so when it happens.

**(c) Beta App Review.** Apple's TestFlight App Review page says: "A review is only required for the first build of a version. Subsequent builds may not need a full review." Its external-testing page adds: "You can only have one build of each version in review at a time," and "You can submit up to six builds for TestFlight App Review within a 24-hour period." External testers can be up to 10,000 per app, and a public link's tester limit can be set between 1 and 10,000. Anyone who has the link can join. Sources: [TestFlight App Review](https://developer.apple.com/help/glossary/testflight-app-review/), [Invite external testers](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers).

Apple gives no latency. Third-party guides say about 24 hours, sometimes up to 48 (unverified). The beta step counts as done when the build is submitted. If Beta App Review rejects the RC, the iOS production step is held with the reason until a fixed RC is on beta. A rejection of the RC is per platform and never holds Play.

**(d) Export compliance.** Apple says that declaring encryption in the Info.plist "eliminates the need to answer encryption questions with each app submission," and that a build without the declaration is marked *Missing Compliance* and must be fixed. Apple does not say whether external testing asks per build, beyond that. With `ITSAppUsesNonExemptEncryption = NO` set in the app and widget Info.plists (WS2), no per-build answer is expected. The value `NO` assumes the app uses only exempt encryption (HTTPS and the system's cryptography), which the owner confirms. Source: [Provide export compliance information for beta builds](https://developer.apple.com/help/app-store-connect/test-a-beta-version/provide-export-compliance-information-for-beta-builds).

**(e) Automating the submit, the phased release and the pause.**

- *fastlane `deliver`* has `submit_for_review`, `automatic_release` (release when approved), and `phased_release` (the seven-day rollout). `automatic_release` and `auto_release_date` cannot be used together. `deliver` has no option to pause, resume or end a phased release. Source: [deliver](https://docs.fastlane.tools/actions/deliver/).
- *App Store Connect API* resource: `appStoreVersionPhasedReleases`, updated with `PATCH /v1/appStoreVersionPhasedReleases/{id}` and the attribute `phasedReleaseState`. Apple's reference pages did not load in this session, so the endpoint and the values come from a forum thread: the values seen there are `ACTIVE`, `COMPLETE` and `INACTIVE`. A third-party MCP wrapper lists `PAUSE` where we expect `PAUSED`. Source: [Apple forum thread 758730](https://developer.apple.com/forums/thread/758730) (unanswered by Apple).
- *Known failure:* in that thread a `PATCH` to `ACTIVE` returned `409` ("You cannot change the state of a phased release in the current version state"), and creating the phased release set `INACTIVE` whatever was requested. Apple has not answered it. The design therefore pauses and resumes only a version that is live and in its rollout, and never during *Pending Developer Release*.
- *Pause budget:* a phased release can be paused "for up to 30 days" in total, with no limit on the number of pauses. A resumed release "will pick up on the day it was left off". Source: [Release a version update in phases](https://developer.apple.com/help/app-store-connect/update-your-app/release-a-version-update-in-phases).
- *Do not use Remove from sale as a brake.* Removing the app from sale ends the phased release for that version, and it "won't be available for that version again". *Release to All Users* ends the rollout early. Neither is used by the train.
- *Spaceship* (the Ruby client fastlane uses): the method names were not verified. WS5 uses the REST resource above directly, or checks the gem's source before it uses Spaceship.

## Why

- **The same three weeks on both stores.** Each stage gets a real audience before the next. Apple's review is one more gate, and the stage that waits for it is the one the train can hold without holding the others.
- **Independence, with one exception.** Play has a crash gate, a ramp and a halt. iOS has the same gates, but its review queue is Apple's, so a stuck iOS version should not hold Play. The one exception is the App Store production step, which needs `main` to contain the version; waiting for Play there keeps "what users run" the same on both stores.
- **Supersede over wait.** A version waiting in Apple's queue can hold the next one for a week per slip. Superseding N-1 costs its review place, which is cheap, because N carries its fixes.
- **Pause, never end.** Apple's phased release can be paused and resumed within 30 days. Removing the app from sale ends it for that version. The brake is a pause, so a false alarm from the Sentry gate costs days, not the rollout.

## Consequences

- WS5 (`feat/ios-staged-train`) records a Stage per platform in the release issue's marker, with a backward-compatible parse of the Play-only markers. It implements the supersede and hold rules above, and tests them in `fastlane/test`.
- The Sentry gate reads each platform's release identifier. iOS's identifier has to match the build (WS2 aligns `release`/`dist` with `MuvissCrashReporting.releaseOf`).
- The app-store phased-ramp job and the brake exist only when the Apple secrets exist. Without them the iOS legs skip with a notice and the Play train runs unchanged.
- The owner runbook (WS7, `docs/RELEASING.md` § iOS) must cover the Individual account, the API key role that can change phased releases (a search snippet of Apple's phased-release page names Account Holder, Admin or App Manager; not fetched), and the export-compliance declaration.
- `docs/RELEASING.md`, `AGENTS.md` and `CLAUDE.md` describe the iOS stages once WS5 lands. This ADR does not repeat them.

## Assumptions the implementer must verify

These are the points Apple did not answer, or answered only in a forum. Each one is a live check before the first iOS production step, and the answer goes on the release issue.

1. **A newer release ends the previous phased rollout** (owner's premise; Apple silent). Verify by reading `phasedReleaseState` of N-1 after N is released.
2. **The phased-release enum spelling.** `PAUSED` or `PAUSE`. Verify with a live `GET` on the resource before the brake ships.
3. **`PATCH` to `ACTIVE`/`COMPLETE` in *Pending Developer Release* returns `409`** (forum report, no Apple answer). The design avoids that state; confirm it does not occur for an automatic release.
4. **One unreleased version at a time per platform** (owner's premise). Apple's FAQ covers review, not an unreleased version; confirm that App Store Connect refuses a new version while one is *Pending Developer Release*.
5. **Beta App Review latency** is unknown (third-party: about 24 to 48 hours). The beta step counts as done on submission, so the latency does not move the schedule; record the observed time on the release issue.
6. **Beta App Review reviews only the first build of a version** in practice (Apple: "might not"). A second RC on a version may take a full review; the Sunday step records the RC it submits.
7. **No per-build export-compliance answer** once `ITSAppUsesNonExemptEncryption = NO` is in both Info.plists (Apple: the declaration removes the per-submission question; external testing not stated). Confirm on the first external upload.
8. **TestFlight builds expire after 90 days** (not checked in this session). The thirteen days between cut and submit are well inside it; confirm.
9. **The owner's `workflow_dispatch` for iOS** is the only override of a Play production hold. This is this ADR's decision; the workflow must make the override explicit in the release issue.
10. **Sentry's `os.name` tag** is set on both platforms' events by the SDKs (iOS through sentry-cocoa, Android through sentry-android). Confirm on the first iOS event before WS2 relies on it; `platform:cocoa` is the fallback filter.
11. **The 30-day pause budget** is cumulative per version, across gate and brake pauses. A release paused for 25 days needs an owner decision before the budget runs out.
12. **Spaceship's method names** for phased release were not verified; use the REST resource or check the gem source.
