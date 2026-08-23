# Muviss

Muviss is a personal, user-focused tracker for movies and TV shows. Users discover titles, save them to a collection, and track how far they've watched. Offline-first; no social features.

## Language

**MediaItem**:
A movie or TV show. The umbrella concept for anything trackable.
_Avoid_: title (ambiguous with a name), entry (that is the saved form).

**MediaId**:
Source-namespaced identity of a MediaItem, rendered as `tmdb:tv:1399` (`source:type:externalId`). Uniquely locates a title within a source and never collides across sources.
_Avoid_: id, key.

**SourceId**:
The origin of media data (e.g. `tmdb`). Open-ended — new sources are added without changing stored data.
_Avoid_: provider (that is the code that talks to a source).

**MetadataProvider**:
The code that fetches media data from one source. TMDB is the only one today; the interface is the extension point for others.
_Avoid_: client, api, service.

**MediaSummary / MediaDetails**:
Summary is the lightweight form used in search results and lists; Details is the full form (genres, runtime, seasons/episodes, production status).

**ProductionStatus**:
Whether a title is still in production. For TV it distinguishes an ongoing show (Returning) from a completed one (Ended/Canceled) — the basis for Watched vs Finished.

**CollectionEntry**:
A MediaItem the user has saved, together with a denormalized snapshot taken at save time so it is browsable offline.
_Avoid_: favourite (that is a separate flag), bookmark.

**Snapshot**:
The copy of render- and status-relevant fields (title, poster, episode list, aired count, production status) stored locally when a title is saved.

**WatchProgress**:
The user's per-episode ticks (and movie seen flag). The single source of truth for how far they've watched.
_Avoid_: history, visibility.

**WatchStatus**:
A value derived from WatchProgress (never stored independently): NotStarted → Watching → Watched → Finished for TV; NotStarted → Watched for movies. Manual status actions mutate progress to keep the two consistent.
_Avoid_: state, phase.

**Favorite**:
An independent boolean flag on a CollectionEntry, orthogonal to WatchStatus.

**SyncEngine**:
The abstraction (`:core:sync`) that pushes/pulls local changes to an optional cloud backend, replaying the `isDirty`/`updatedAt`/soft-`deleted` change-log ADR 0002 put on every user-owned table before any backend existed. Talks to backends only through `SyncBackend` (ADR 0009) — Supabase is the only implementation today. Conflict resolution is last-write-wins on `updatedAtEpochMs`, per row.
_Avoid_: backend, cloud (those are vendors behind this seam).

**Triage**:
Deciding rapidly, one MediaItem at a time, whether it belongs in the collection and how far the user has already watched it. The mechanism that fills an empty collection quickly, and afterwards keeps offering newly-surfaced MediaItems the user has never ruled on.
_Avoid_: swipe, deck, card (those are UI mechanics, not domain).

**TriageDecision**:
The persisted record of one TriageVerdict on one MediaItem — a log of what the user decided, never a status. Outlives the CollectionEntry it created: removing a MediaItem from the collection does not erase the decision, so triage never asks about it twice.
_Avoid_: dismissal, rejection (Skip is reversible); triage state (a decision is an event, not a state).

**TriageVerdict**:
The four outcomes of triaging one MediaItem. Skip — not for me. Later — collected, not started. Watching — collected, started (TV only; a movie is never in progress). CaughtUp — collected, every aired episode seen. The last three write a CollectionEntry and, for Watching and CaughtUp, real WatchProgress ticks; none of them stores a WatchStatus, which stays derived (ADR 0005).
_Avoid_: watchlist (that is a CollectionEntry with NotStarted status), seen (that is an episode tick).
