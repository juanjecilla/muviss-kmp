# Triage: tapping the new top card opened the one just swiped away

## Symptom

In the triage card stack, swiping a card away (skip, or any other verdict —
drag or button) and then tapping the new top card to view its detail opened
the card that had just been dismissed instead.

## Root cause

`SwipeCard` (`feature/triage/ui/.../SwipeDeck.kt`) chained two gesture
detectors on the same `Box`:

- drag: `Modifier.pointerInput(scheme, available, cardId) { detectDragGestures(...) }` — correctly re-keyed on `cardId`.
- tap: `Modifier.pointerInput(Unit) { detectTapGestures(onTap = { ... onTap() }) }` — keyed on `Unit`, a key that never changes.

`pointerInput`'s coroutine only restarts (cancel + relaunch, picking up a
fresh closure) when its key changes between recompositions. With `Unit` as
the key, the tap coroutine launched once and kept running the closure it
captured *then* — bound to whichever card was on top when this `SwipeCard`
slot first composed. The card shown on screen updated correctly every swipe
(the `content` lambda re-executes normally), but tapping kept calling
`onOpenDetail` with that first-composed card's id.

This was masked as long as the composable slot itself got torn down between
cards, but it doesn't: `DeckArea` wraps each *backing* card in
`key(backing.id) { ... }` (`TriageScreen.kt`), but the **top** card's
`SwipeCard(...)` call has no such `key(top.id)` wrapper — it's the same
composition slot across every swipe, so the stale tap detector (and its
frozen closure) survives.

Confirmed by reproduction, not just by reading the code: a controlled test
against `SwipeCard` directly showed the tap coroutine is engaged lazily (it
only actually starts once the card has been interacted with, not eagerly on
composition), so a synthetic "swap the card id and tap immediately" test does
not reproduce this — the coroutine has to have genuinely started for the
first card before the swap, exactly as happens in the running app once a card
has been on screen for any length of time.

## Fix

Rekey the tap detector the same way the drag one already is:

```kotlin
.pointerInput(scheme, available, cardId) {
    detectTapGestures(onTap = { if (!state.busy) onTap() })
}
```

No change was needed in `TriageViewModel` (its list-popping was already
correct) or to add the missing `key(top.id)` in `TriageScreen.kt` — the
`pointerInput` rekey alone fixes it, and matches the pattern the drag
detector already used right next to it.

## Regression tests

- `feature/triage/ui/src/jvmTest/.../TriageScreenTest.kt` —
  `tapping_the_new_top_card_after_a_swipe_opens_it_not_the_skipped_one`:
  drives the real screen end to end (skip a card via drag, tap the new top
  card, assert the opened id is the new card's).
- `feature/triage/ui/src/jvmTest/.../SwipeDeckStateTest.kt` —
  `the_tap_detector_follows_the_card_when_it_changes`: exercises `SwipeCard`
  directly with no `key()` wrapper (matching `TriageScreen`'s real call
  site), taps the first card to engage its tap detector, swaps the card id,
  and asserts a second tap reaches the new card rather than the first.

## Follow-up

[Issue #133](https://github.com/juanjecilla/muviss-kmp/issues/133) audits
other `detectTapGestures`/`detectDragGestures` sites in the repo for the same
class of bug (a recycled composable slot with a gesture detector keyed
differently from the item identity).
