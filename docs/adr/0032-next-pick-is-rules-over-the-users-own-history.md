# Next Pick is rules over the user's own history, behind a ranker seam

EPIC 51 (#396) asks the app to help after a Completion: a short Pick Session, then a Next Pick. Something has to choose the pick.

## Decision

- **Rules, TMDB and local history. No model in v1.** Candidates come from the user's backlog (NotStarted entries, lists) and from TMDB (`/discover` with the session's filters, `/recommendations` and `/similar` seeded by the title just finished or by `ForYouSeeding`). Ranking is a scored sum over ratings, favorites, genre overlap, the Taste Profile and the answers given. Every pick carries a localized reason built from the same inputs that scored it.
- **A ranker seam.** Candidate gathering and ranking are separate interfaces; the engine hands a ranker `PickCriteria` plus candidates and takes back an ordered list with reasons. An AI ranker replaces the scoring without touching sources, filters, exclusions or UI. What an AI option would cost is in `docs/research/next-pick-ai.md`; the evaluation is #404.
- **Actions reuse existing writes; there is no pick log.** Add is a CollectionEntry, Not for me is a TriageDecision Skip, Not now is a Snooze (ADR 0023). Skip is what makes a refused pick leave the Discover deck as well, and the exclusions Triage already runs (`LoadDeckUseCase`) are the exclusions Next Pick needs. Session answers are never stored.
- **No implicit learning.** Nothing adjusts weights from what the user accepts or refuses. Hidden state the user cannot see or undo is the thing ADR 0023 warns about; a learned model needs a visible, resettable form and outcome data we will not have until this ships (#405).
- **Muted Genres apply** (ADR 0031), with its rule that an explicit choice beats a standing one: a genre the user picks in the session is shown even if muted, other muted genres still apply inside it.
- **Taste Profile holds likes only.** Dislikes are Muted Genres; two lists for one idea would disagree. The profile is user data (synced, exported, hard-deleted with Delete all data), unlike Muted Genres, which stay per-device until #385.
- **Free, offline-capable.** Backlog picks need no network; offline the session says so and offers the backlog only.
- **Completion is user-made only.** The prompt follows a tick in the app, never an import, a sync pull, a restore or a bulk mark. At most once a day, with a Settings switch.

## Why

- The app is offline-first, free and has no backend of its own beyond optional sync. A model that needs a service or a key would break one of those on day one.
- The questions already carry most of the signal. "An evening, something light, not another cop show" is a filter over data TMDB returns, not a prediction.
- Reusing Skip and Snooze keeps one answer to "has the user ruled on this" across Triage, Discover and Next Pick.

## Alternatives not taken

- **LLM ranker in v1.** Cost, key custody and privacy for a result rules can mostly reach. Kept open as a spike.
- **A `nextPickDecision` log.** A second source of truth for refusals; Triage would not know about it, so a title refused here would come back in the deck.
- **A fifth TriageVerdict.** ADR 0023 already showed why an unknown verdict corrupts older builds' reads.
- **Learning from outcomes in v1.** See above.

## Consequences

- The engine is testable without a network or a model: criteria and candidates in, ordered picks out.
- Quality is capped by what TMDB filters can express (mood is a genre-set mapping, not a feeling). The spike exists to find out whether that cap matters.
- `MetadataProvider.discover` gains defaulted filters (runtime, language, date, providers, certification); hand-written fakes must take them (ADR 0031's lesson).
- Taste Profile sync follows #385's row-per-item shape, and is effectively local until sync ships to production.
