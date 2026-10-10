# Contributing

Thanks for looking. Muviss is a personal, offline-first movie and TV tracker built with Kotlin Multiplatform; read the [README](README.md) to get it building.

The working agreement — branches, PR titles, merging, what "done" means, where things get written down — is in **[AGENTS.md](AGENTS.md)**. It is written for AI agents and humans alike, and it applies to both. The short version:

- Branch from `develop`, open the PR against `develop`, title it as a conventional commit (`fix(search): …`). `main` only takes release and hotfix merges.
- Run `./gradlew spotlessCheck detekt` and the tests of the modules you touched (`git config core.hooksPath .githooks` runs the first two on every commit).
- A user-visible free feature updates the landing in the same PR (`website/src/i18n/en.ts` and `es.ts`).
- Anything you found and didn't fix gets an issue.

Useful background: [CLAUDE.md](CLAUDE.md) (module map and the traps that bite), [CONTEXT.md](CONTEXT.md) (the domain glossary — please use its terms), [docs/adr/](docs/adr/) (why things are the way they are) and [docs/TESTING.md](docs/TESTING.md).

Security issues: see [SECURITY.md](SECURITY.md), not the issue tracker.

Muviss has no social features by design, with one narrow exception (ADR 0022). Proposals that add feeds, profiles or followers will be declined.
