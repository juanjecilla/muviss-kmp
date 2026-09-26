# A snooze is not a verdict

Triage (ADR 0010) offers four verdicts. A film gets three of them, because `WATCHING` is meaningless for something you are never partway through — which is how this started: "give a film a fourth action, *don't decide now*".

That framing does not survive contact with `CONTEXT.md`. A `TriageDecision` is "a log of what the user decided", and it exists so that "triage never asks about it twice". "Don't decide now" is the refusal to decide, and its entire purpose is to be asked again.

## Decision

**Snooze** is a concept beside the verdicts, not inside them: a `triageSnooze` table keyed by `mediaId`, holding a due date and a snapshot of the card, synced like any other user-owned table. Recording any verdict retires a Snooze; a title never holds both.

It is reached from a button on the card, not from `VerdictButtonRow`. That row's own KDoc records that four side-by-side labels already overflowed a 1080px phone and now ellipsize at `maxLines = 1`; a fifth column is not available. A long press and the `S` key are accelerators on top of the button, which is the affordance that exists on all six targets — a mouse has no long press.

### Why not a fifth `TriageVerdict`

This is the option the request implies, and it is actively dangerous rather than merely inelegant. `TriageVerdict.fromStored` returns null for a value it does not know, so `SqlDelightTriageDecisionRepository` drops the row from `observeByVerdict` and `observeDecision` — while `selectDecidedIds` reads ids straight from SQL and still counts it. An older build, or a peer device that has not updated, would therefore exclude the title from its deck **permanently**, with no screen able to show or undo it. A table an old build does not know about is simply never pulled.

### Why it syncs

ADR 0010's precedent: triage intent is user data. Being asked on the desktop about something already postponed on the phone is the same failure as being asked twice, and the answer is the same.

Deletes are **soft**, like `episodePlay` (ADR 0013), and every read filters `deleted = 0`. Last-write-wins cannot express a hard delete: a physically removed row has no timestamp left to compare, so the other device would push its copy back and an unsnooze would undo itself.

### Why the row carries the whole card

`MetadataProvider` has no `summary(id)` — only `details(id)`, which for a show costs `1 + ceil(seasons / 20)` requests and deliberately bypasses `HttpCache`. Rehydrating a due batch through it would be the most expensive read in the app. So `title`, `year`, `posterUrl` and `overview` travel with the Snooze, following `collectionEntry`'s snapshot precedent.

The accepted cost: `overview` is frozen in whatever TMDB language was active when the title was snoozed.

### Why due snoozes are a second source, interleaved

The deck is TMDB `/discover` by popularity, and `LoadDeckUseCase` could only ever *exclude*. A title postponed three months ago is not on page 1 when it returns, so holding onto it and feeding it back is the only way it can come back at all.

They are capped at two per ten-card batch by default (`SnoozePlacement.MIXED_IN`) and never take the first slot. Forty due titles ahead of anything new is precisely the "tidied-up library resurfacing card by card" failure ADR 0010 exists to prevent. `FIRST` and `LAST` exist for people who would rather clear them in one go, or never be interrupted.

A due Snooze still passes through the exclusion set. One whose title has since been decided or collected elsewhere has no question left to ask, so it is retired — soft, so the tombstone travels.

## Considered options

- **A fifth `TriageVerdict`.** Cheapest: no table, no migration, free sync reuse. Rejected for the poisoning above, and because it puts a non-decision in the decision log.
- **A `deferredUntil` column on `triageDecision`.** Avoids the unknown-verdict problem and still reuses the wire type. Rejected: a row in the decision log that is not a decision makes `selectDecidedIds` — the deck's exclusion set, and the promise the table exists to keep — mean two different things at once.
- **Session-only, nothing persisted.** The card returns on the next launch and there is no schema change at all. Rejected: it cannot express "ask me in a month", it does not cross devices, and a mis-press is unrecoverable.
- **A fifth button in the verdict row, with a setting choosing which TV verdict it displaces.** What the request originally asked for. Rejected once the row's overflow history was read: it makes one verdict unreachable from the deck, needs a second setting to say which, and ellipsizes four labels further.

## Consequences

- A second table can hold a title the decision log does not. That is the point, and the two are kept mutually exclusive rather than reconciled.
- Snoozed titles are filtered out of Discover's "For you" while pending, alongside skips — but **not** out of search results or the Popular carousels, for the same reason ADR 0010 gives.
- Adding a synced table costs the six touchpoints ADR 0009 enumerates, including hand-run SQL in `docs/SYNC.md` and a `FakeSupabaseServer` schema.
- `SnoozePeriod.ASK_EACH_TIME` opens a dialog offering the three presets. A freely chosen date is **not** implemented: Material3's `DatePicker` is unverified on `js`/`wasmJs` and there is no `kotlinx-datetime` in the project.
- The deck header now carries three text actions beside the title, which wraps to two lines on a phone.
