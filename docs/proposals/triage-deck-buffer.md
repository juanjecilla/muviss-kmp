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
- **Persist the cursor per device.** Key: type + genre + mute fingerprint + TMDB language. Value: next page per type and `savedAt`. **TTL 24 h; resume two pages before the saved page; older → page 1.**

## Why 24 h and a rewind
TMDB documents popularity as a "lifetime" score built from daily counts (views, votes, favorites, watchlist adds, the previous day's score). It publishes no recompute schedule, so treat the order as moving daily: a title can climb above the saved page and be skipped. Rewinding two pages absorbs small drift; the TTL bounds the rest. Discover stops at page 500 (a TMDB support answer, not the API reference, which states no maximum), so a cursor at the cap with a valid TTL must end as "caught up", not loop back.

## Corner cases
Reset mid-refill; process death; two filter keys with different cursors; offline (keep the deck, no spinner while cards remain); Snooze injection still never takes the first slot; undo of a verdict returns the card to the front; deck seeded from Next Pick (#554/#555) bypasses the cursor.

## Data
New table (migration number is claimed at merge; 26 is #642's). Per-device like a Device Switch: not synced, not in the export, not restored; `SqlDelightLocalDataEraser` wipes it; `ExportShapeTest` and `SyncChangeSetShapeTest` pin its absence.

## Tests
Swipe during refill does not restart (count pages read); buffer ≥ low-water; overflow kept; new ViewModel resumes from cursor; TTL and rewind; reset cancels; cap reached ends as caught up; performance budget (`TriagePerformanceBudgetTest`) still bounds a fully-decided catalogue.
