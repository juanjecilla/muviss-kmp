<!-- Base branch: `develop` for day-to-day work. Only `release/*` and `hotfix/*` target `main` (ADR 0025). -->

## What

## Why

## Verified
<!-- What you ran, on which targets. Say what you could not verify, and link the issue you filed for it. -->

## Checklist
- [ ] `./gradlew spotlessCheck detekt` and the affected modules' tests pass
- [ ] **Landing**: a free, user-visible feature is on muvissapp.com — `website/src/i18n/en.ts` + `es.ts` (and the Play listing in `fastlane/metadata/android/*/full_description.txt` if it changes what the app does). Paid features (sync, co-watch) never go there. Not applicable → label `no-landing`.
- [ ] Anything raised and not done here has an issue (AGENTS.md)
