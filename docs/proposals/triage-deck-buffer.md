# Triage: a deck that is rarely empty

Issue #722. Follow-up to #183 (its "persist the cursor" option was never built).

## Problem
Users with many decided titles reach an empty deck, a spinner or "Keep looking". From reading the code (not reproduced):
1. Every swipe at ≤3 cards calls `refill`, which starts with `loadJob?.cancel()`. The cursor moves only when a whole `loadDeck` returns, so for a user whose batch costs up to five sequential `/discover` calls the refill is killed before it lands.
2. `DeckCursor` is a ViewModel field: each visit restarts at page 1.
3. `DeckLoader.load` gathers whole 20-title pages and returns `merged.take(wanted)`. The cursor has moved past the rest and `shown` never learned them.
4. Batch 10 / threshold 3 / the stop rule counts empty batches, not pages.

## Proposal
- **One refill at a time.** A swipe never cancels. Reset (type/genre/mute change, retry) cancels with a generation token so a late result is dropped.
- **Buffer**: low-water 5, target 10. Append each batch on arrival. Budget by pages read per refill, not by empty batches.
- **Keep the overflow** as a queue behind `cards`, drained before any network call.
- **Read the exclusion set once per refill** (decided ids, library, snoozes) instead of once per batch.
- **Persist the cursor per device.** Key: type + genre + mute fingerprint + TMDB language. Value, per type: `nextPage`, `exhausted` (today's `DeckCursor` tracks it separately from the page), the earliest page that still holds a card the user has not acted on (`resumePage`), and `savedAt`. When no card is pending (a fully decided batch can advance `nextPage` without producing one) `resumePage` is `nextPage`, and an `exhausted` type stays exhausted until the TTL sends it back to page 1. **TTL 24 h; resume two pages before `resumePage`; older → page 1.** A batch can span five pages, so "two before the next page" would skip cards from the first pages of a batch the user never reached before the process died; checkpointing the earliest page with an unacted card cannot. Re-reading is cheap and safe: decided ids are excluded on the way in. **The guarantee is best effort**: it holds while the ranking is stable. If drift moves an unacted title from page 100 to page 97 and the resume lands at 98, it is skipped until the TTL runs out or the next page-1 walk. If exact recovery were required, the pending cards (about ten rows: id, title, year, poster, overview) would be persisted instead; not worth a table today.

## Why 24 h and a rewind
TMDB documents popularity as a "lifetime" score built from daily counts (views, votes, favorites, watchlist adds, the previous day's score). It publishes no recompute schedule, so treat the order as moving daily: a title can climb above the saved page and be skipped. Rewinding two pages absorbs small drift; the TTL bounds the rest. Discover stops at page 500 (a TMDB support answer, not the API reference, which states no maximum), so the stored value is the *next* page to read: a cursor of 500 still has page 500 to read, and the catalogue is exhausted only after the last available page has been read: `min(totalPages, 500)` from the response (a filtered result can have fewer than 500 pages), which is what `DeckLoader` does today with `page >= totalPages`. A cursor at 500 with `totalPages` of 500 still has page 500 to read.

## Corner cases
Reset mid-refill; process death; two filter keys with different cursors; offline (keep the deck, no spinner while cards remain); Snooze injection never replaces a card already displayed at the top (the existing `SnoozePlacement.FIRST` and `MIXED_IN`-with-no-fresh-cards cases, which deliberately put due Snoozes first, are unchanged); undo of a verdict returns the card to the front; deck seeded from Next Pick (#554/#555) bypasses the cursor.

## Data
New table. AGENTS.md: claim the migration number in `docs/EPICS.md` the moment the implementation branch opens, not at merge (26 is #642's, so the next free one); this docs-only branch claims nothing. Per-device like a Device Switch: not synced, not in the export, not restored; `SqlDelightLocalDataEraser` wipes it; `ExportShapeTest` and `SyncChangeSetShapeTest` pin its absence.

## Tests
Recovery after process death with a batch spanning more than two pages skips no unacted card; swipe during refill does not restart (count pages read); buffer ≥ low-water; overflow kept; new ViewModel resumes from cursor; TTL and rewind; reset cancels; exhaustion after page `min(totalPages, 500)` has been read, including a filtered result shorter than 500 pages; performance budget: `TriagePerformanceBudgetTest` calls `loadDeck` once and checks the per-batch limit, so add a refill-level test that drives `TriageViewModel.refill` across all its batches against a fully decided catalogue and counts every page read.
