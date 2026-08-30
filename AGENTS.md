# AGENTS.md

Instructions for any agent working in this repo. **Read `CLAUDE.md` first** — it
carries the architecture, the domain rules that bite, and the setup gotchas.
This file carries the working agreement.

## Undone work gets an issue, in the same session that found it

**Anything raised during a session and not done in that session must exist as a
GitHub issue before the session ends.** An epic, a bug, or a task — the label
matters less than the number.

This applies to every one of these, and the list is not a formality:

- **A gap you chose to leave.** Scoping something out is a decision; an
  undocumented one looks identical to an oversight six weeks later.
- **A bug you found in code you weren't touching.** Especially then. It will not
  find itself twice.
- **Something you could not verify.** "Compiles but never ran", "needs a device I
  don't have", "needs credentials this repo doesn't hold" — say which, and say
  what would prove it.
- **A trap you hit and worked around.** The workaround is in the diff; the trap
  is only in your head.
- **A follow-up the work implies but doesn't include.** New seam with one
  implementation, new table nothing reads yet, new flag with one branch.

### Why

Two habits this repo already has make the cost of skipping it concrete.

Work is scoped so **a single agent implements an epic end to end**, and epics run
in parallel. An agent starting the next one cannot read the last one's mind — it
reads `docs/EPICS.md` and the tracker. Anything that lives only in a session
transcript is, for every practical purpose, gone.

And the failures worth recording are exactly the ones tests do not catch. EPIC 22
shipped green — a full `./gradlew build` across six targets and two thousand
passing tests — and **the app did not start**, because a Koin construction cycle
is only visible when something is actually built. Running it on an emulator found
that and one more. Neither would have been in the diff, and neither was
predictable from the code.

### What a good issue looks like

Not "TODO: fix widget preview". State **what is wrong, what you already know
about why, and what would count as done.** Paste the error. Name the file. Say
what you ruled out. If you verified it is pre-existing rather than yours, say how
you verified it — a future reader should not have to redo that.

If it turns out to be an epic rather than a task, add it to `docs/EPICS.md` as
well and link the issue both ways.

### Where it goes

- **`docs/EPICS.md`** — the map. Every backlog entry carries its issue number.
- **GitHub issues** — the source of truth for undone work.
- **`docs/adr/`** — only for decisions that are hard to reverse, surprising
  without context, and the result of a real trade-off. Not a place to park a
  TODO.
- **`CLAUDE.md`** — for traps that would bite the next agent *while they are
  editing this code*, which is a smaller set than it looks. A trap that only
  matters once belongs in a code comment.

### The one exception

Something you fixed in the same session does not need an issue. Write it in the
commit message instead, with the reasoning — this repo's commit messages carry
the "why", and reviewers read them.

## Before you finish

- `./gradlew spotlessCheck detekt` — what the pre-commit hook runs.
- `./gradlew build allMetadataJar` — every target, not only Android. Android-first
  (ADR 0003) is a **verification order, not a scope limit**.
- Run the thing if it can be run. `./gradlew :app:androidApp:installDebug` against
  an emulator costs minutes and catches what tests structurally cannot.
- Re-run tests with `--rerun-tasks` after a rebase. Gradle will happily call a
  suite up-to-date from before the merge, and CI's clean checkout will not.
