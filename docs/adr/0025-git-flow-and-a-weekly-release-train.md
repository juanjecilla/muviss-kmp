# Git flow, a weekly release train, and production gets the tested RC

ADR 0024 shipped every store from one `vX.Y.Z` tag pushed by hand onto `main`, and `main` took every merged PR. That worked while nothing had ever been released. Once versions reach users, `main` has to mean "what users run", and building a release has to stop depending on someone remembering to cut it. This supersedes ADR 0024's "one tag triggers everything" mechanism. Its other decisions stay: local-only v1, fastlane, internal first, and one version line for every platform.

## Decision

**Git flow.**
- `develop` is the default branch, and every day-to-day PR targets it (squash merge).
- `main` only ever receives `release/X.Y.Z` and `hotfix/X.Y.Z` branches, by merge commit, so `main` and `develop` share history and the back-merge stays clean.
- A PR check (`main-source-guard`) enforces the head-branch rule, because rulesets cannot express it.
- Rulesets on both branches require a PR, CI and one approval. The approval is CodeRabbit's (free for public repos), given once its comments are resolved. Admins can bypass.

**Only release branches and `main` distribute anything.**
- Every push to `release/*` or `hotfix/*` is a release candidate. It is tagged `vX.Y.Z-rcN`, built as a signed AAB, uploaded to Play's internal track, and published as a GitHub pre-release.
- `develop` builds nothing distributable.

**Production gets the RC binary, not a rebuild.** Merging the release PR into `main`:
- tags `vX.Y.Z` on the merge commit;
- promotes the newest RC from internal to production at 20%, behind the `production` environment's required reviewer;
- attaches that same RC AAB, plus the desktop installers, to the GitHub Release;
- opens a back-merge PR from `main` into `develop`.

The RC already carries the final `versionName`: the version pattern drops `-rcN`.

**A weekly train cuts the release.**
- A nightly cron runs on `RELEASE_TRAIN_DAY` (default Monday, 23:00 UTC).
- It cuts `release/<next>` from `develop` if `develop` has non-merge commits since the newest final tag.
- The version comes from conventional commits: breaking → major, `feat` → minor, otherwise patch.
- One release is in flight at a time. While a `release/*` branch exists the train skips and reports on a "Release train blocked" issue. Hotfixes are exempt.

**The rollout ramps itself.** A daily job raises production along a ramp counted from the release tag's age: day 0 → 20%, day 2 → 50%, day 4 → 100%. It only ever raises the fraction and never touches a halted release. Halting and resuming are manual (`play-promote.yml`).

**A bot identity for automation.** A private GitHub App, `muviss-release`, pushes the train's branches and the release tags, and opens back-merge PRs. Anything done with `GITHUB_TOKEN` starts no workflow: a train branch pushed with it would never build an RC, and a back-merge PR opened with it would never get CI. A tag ruleset limits `v*` tags to admins and that App.

## Why

- **`versionCode` is `git rev-list --count HEAD`**, and it has to rise on every upload. If `develop` uploaded builds, a later release from `main` could carry a lower count than an earlier `develop` build, and Play would reject it. Keeping uploads to release branches, whose count grows into `main`, keeps the scheme without inventing a new one.
- **Promoting the RC means production runs exactly the bits testers had.** A rebuild on `main` would differ only by a merge commit. But it would be a binary nobody installed, and the store path is the one place where "nobody ran this" has bitten this repo before (EPIC 22).
- **The train turns "someone remembers to release" into "a release happens unless someone stops it".** Skipping when nothing changed avoids empty releases. Skipping while a release is open avoids two candidates fighting over the internal track and "promote the latest".
- **The ramp is computed, not stored**, so nothing has to write repo state from a workflow. Halting is the brake because Play already models it. There is no crash-rate gate yet: Sentry session pings were dropped in #145, so a crash-free rate is not available. Gating is a follow-up issue.

## Consequences

- PRs target `develop`. `docs/RELEASING.md`, `AGENTS.md` and `CLAUDE.md` describe the flow. `scripts/release/start-release.sh` replaces `cut-release.sh`.
- Release notes are written on the release branch, in `fastlane/release-notes/vX.Y.Z/<locale>.txt`. The train seeds them as drafts, and a PR into `main` fails while the draft marker remains.
- The landing deploys from `main`, so website copy merged on `develop` goes live when its feature is released. A PR check (`landing-guard`) makes every `feat` PR that touches the app touch `website/` too, or carry the `no-landing` label.
- Owner setup (the App, rulesets, environment, keystore, Play account) is `scripts/release/owner-setup.sh`.
- iOS (TestFlight) and signed desktop installers are not in the train yet (#78, #79, #179). When they are, they join the RC and release jobs and keep the same version line.
