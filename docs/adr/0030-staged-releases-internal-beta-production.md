# Staged releases: a week on internal, a week on open beta, then production

ADR 0025 gave each release a Monday cut, an internal RC, and a hand-merged PR into `main` behind a production approval. One release could be open at a time. This ADR keeps the weekly cut but gives every version three weeks, one per stage. It amends ADR 0025's "one release in flight", "the owner merges into `main`" and "`versionCode` is the commit count". Its other decisions stay: git flow, production runs the RC binary, the computed ramp, and the release App.

## Decision

**Every version walks three stages on Play, unless something stops it.**

| When | Stage | What happens |
|---|---|---|
| Monday 23:00 UTC (week 0) | internal | The train cuts `release/X.Y.Z` and the RC goes to the internal track, as before. |
| Sunday (week 0, six days later) | open beta | The same RC binary is promoted from internal to the beta (open testing) track. |
| Sunday (week 1) | production | `release/X.Y.Z` is merged into `main`; `release.yml` tags `vX.Y.Z` and promotes the beta release to production at 20%. The daily ramp takes it to 100%. |

- **Up to three versions are in flight**, at most one per stage: on a Monday, one version is ramping in production, one is in beta, and the train cuts a new one for internal. The train no longer skips because a `release/*` branch exists; it skips only when `develop` has nothing new, or when a version is still sitting in internal (it was held, so the new cut would collide on that track).
- **The production step is fully automatic.** The `production` environment approval is dropped from the scheduled path; the gates below and the crash-gated ramp are what stop a bad version. The manual brakes stay: `play-promote.yml` halts and resumes, and a `release-blocker` issue stops the next step.

**A promotion happens only when every gate passes.** The Sunday job checks each version due a step, and holds it (and says why on its release issue) if any of these fail:
- an open issue or PR in the version's milestone labelled `release-blocker`;
- an open PR into its `release/X.Y.Z` branch (a fix in flight);
- the Sentry crash gate (ADR 0025's, `fastlane/sentry_gate.rb`) finds an unhandled issue first seen in that build;
- the store notes still carry the `DRAFT:` marker (beta testers see them too, so this is checked at the beta step, not only at production);
- the branch head is not the newest RC (a commit landed after the last candidate was built), or that RC's build or Play upload did not succeed;
- CI on the branch head is not green;
- for production: the version has not had its full week on beta (a held beta step pushes production back with it).

A held version is retried every Sunday, and by hand (`release-promote.yml`, dispatch). Production, as today, never rebuilds.

**Each version has a milestone and a release issue.** The train creates milestone `X.Y.Z` and an issue "Release X.Y.Z" carrying its schedule (internal / beta / production dates), the RC it is on, and every hold with its reason. The Sunday job and `release.yml` update it; it closes when the version reaches 100%. The milestone is how work is pinned to a version: a PR into `release/X.Y.Z` gets the milestone automatically, and `release-blocker` is a label anyone can put on an issue in it.

**A fix targets the version it is for, and moves forward by itself.** A PR merged into `release/X.Y.Z` (or `hotfix/X.Y.Z`) opens forward PRs, as the release App, into every newer open `release/*` branch and into `develop`, each linking the original. A forward PR that conflicts stays open for a person; a forward PR into a release branch is itself a fix in flight, so it holds that version's promotion until it merges. Each push to a release branch is still a new RC.

**`versionCode` comes from the version, not the commit count**: `major × 1 000 000 + minor × 10 000 + patch × 100 + rcN` (for example 1.1.0-rc4 → 1010004). With several release branches, commit counts on different branches can coincide, and Play rejects a reused `versionCode`. The formula is unique per candidate, rises with every version and every RC, and starts above every count-based code already uploaded (the highest was under 200). It caps `rcN` at 99 and minor and patch at 99. A hotfix is a patch version, so it fits.

## Why

- **Each stage gets a real audience before the next**: internal is the owner's own devices, open beta is volunteers on many devices, production is everyone. Six to seven days per stage is long enough to see crash reports and reviews, and short enough that a weekly cadence still ships weekly.
- **A release happens unless someone stops it**, as in ADR 0025, now at every stage. The owner can test any version on any track, and the default is to ship.
- **Fully automatic production was chosen over keeping the approval click**, because every condition the owner would check before clicking is a gate, and the ramp's crash gate still holds the rollout at 20% if the build crashes. The trade-off: a regression nobody filed and Sentry does not see reaches 20% of users before anyone looks. `play-promote.yml` halt is the brake.
- **Forward propagation keeps the newer lines fixed.** With three branches open, a fix made only on the oldest would quietly disappear in the next version. Opening the forward PRs automatically makes forgetting the exception, not the rule.

## Consequences

- `release-train.yml` stops skipping on an open `release/*` branch (only on one still in internal), and creates the milestone, the release issue and the `release-blocker` label.
- A new `release-promote.yml` runs every Sunday (and on dispatch) and does the beta and production steps; the production step merges `release/X.Y.Z` into `main` with a merge commit, as the release App, so `release.yml` starts.
- `release.yml` promotes from the **beta** track instead of internal, and its production job loses the `production` environment on this path. The back-merge into `develop` is now mostly empty, because fixes were already forwarded; it stays as the safety net and carries the tag.
- The fastlane `promote` lane gains internal → beta. `app/androidApp/build.gradle.kts` derives `versionCode` from the version, and the RC workflow passes `rcN`.
- `docs/RELEASING.md`, `AGENTS.md` and `CLAUDE.md` describe the stages, the gates and where to point a fix.
- iOS (TestFlight internal → external → App Store) and desktop can follow the same three stages when they join the train (#78, #79); this ADR does not cover them.
