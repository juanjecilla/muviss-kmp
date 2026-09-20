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
The copy of render- and status-relevant fields (title, poster, aired count, production status) stored locally when a title is saved, so a CollectionEntry is browsable offline. Written once, at save time, and refreshed with the library — not the episode list, which is the EpisodeCatalog and has its own lifecycle.
_Avoid_: cache (that is the EpisodeCatalog).

**EpisodeCatalog**:
A show's seasons and episodes as the source reported them, stored locally and refreshed on its own cadence. Not the user's data — nobody authors it, a refetch always wins over what is stored, and it is neither synced nor exported. Distinct from a Snapshot, which is the user's saved row.
_Avoid_: snapshot, episode list, seasons (as a stored thing).

**WatchNext**:
The titles a user is part-way through, each paired with the next episode they have not seen. Derived from CollectionEntry, EpisodeCatalog and WatchProgress together, never stored. The same answer wherever it is asked — the Progress tab and the home-screen surfaces are renderings of it, not variants of it.
_Avoid_: up next, continue watching, queue (a watchlist is a CollectionEntry with NotStarted status); upcoming (that is future air dates, a different question).

**WatchProgress**:
The user's per-episode ticks (and movie seen flag). The single source of truth for how far they've watched.
_Avoid_: history, visibility.

**WatchStatus**:
A value derived from WatchProgress (never stored independently): NotStarted → Watching → Watched → Finished for TV; NotStarted → Watched for movies. Manual status actions mutate progress to keep the two consistent.
_Avoid_: state, phase.

**Favorite**:
An independent boolean flag on a CollectionEntry, orthogonal to WatchStatus.

**Play**:
One recorded viewing of one element — an episode, or a movie via its single synthetic element. Carries the moment it happened. Progress answers whether something was seen; a Play is the event that made it so, and the reason "three times, most recently in March" is answerable at all. A Play's identity comes from the viewing itself, not from the device that recorded it, which is what lets rewatch history cross devices (ADR 0013).
_Avoid_: watch, view (verbs, not records); tick (that is the act of marking seen); play count (that is derived from these, never stored).

**Rewatch**:
A Play with an earlier Play of the same element behind it, at any date. Derived, never stored. The unit every "most rewatched" number counts: a title's score is its Plays beyond the first, per element, so a first watch-through scores zero however long it is. A time window bounds the Rewatch itself and never the Play before it — an episode first seen in December and watched again in March is a Rewatch *in March*.
_Avoid_: "most seen" (_seen_ is the stored boolean tick); replay; play count (that is a total, not the beyond-the-first count).

**SyncEngine**:
The abstraction (`:core:sync`) that pushes/pulls local changes to an optional cloud backend, replaying the `isDirty`/`updatedAt`/soft-`deleted` change-log ADR 0002 put on every user-owned table before any backend existed. Talks to backends only through `SyncBackend` (ADR 0009) — Supabase is the only implementation today. Conflict resolution is last-write-wins on `updatedAtEpochMs`, per row, between unsent edits (a clean local row just takes the server's copy). What gets pulled is decided by a server-assigned change sequence, one cursor per table, never by a client clock (ADR 0020). Records whose library the local database belongs to, and stops on a different account. Refuses to run without an Entitlement (ADR 0018).
_Avoid_: backend, cloud (those are vendors behind this seam).

**Entitlement**:
Whether the user currently holds the paid feature. Distinct from whether the build *has* the feature at all: a build without it shows nothing, a build with it that the user has not paid for shows the feature and a way to buy it. Independent of being signed in — one is bought, the other is an account (ADR 0018).
_Avoid_: subscription, purchase, licence (those are how an Entitlement is acquired, and vendors behind that seam); premium, pro (those name a tier this app does not have).

**Triage**:
Deciding rapidly, one MediaItem at a time, whether it belongs in the collection and how far the user has already watched it. The mechanism that fills an empty collection quickly, and afterwards keeps offering newly-surfaced MediaItems the user has never ruled on.
_Avoid_: swipe, deck, card (those are UI mechanics, not domain).

**TriageDecision**:
The persisted record of one TriageVerdict on one MediaItem — a log of what the user decided, never a status. Outlives the CollectionEntry it created: removing a MediaItem from the collection does not erase the decision, so triage never asks about it twice.
_Avoid_: dismissal, rejection (Skip is reversible); triage state (a decision is an event, not a state).

**TriageVerdict**:
The four outcomes of triaging one MediaItem. Skip — not for me. Later — collected, not started. Watching — collected, started (TV only; a movie is never in progress). CaughtUp — collected, every aired episode seen. The last three write a CollectionEntry and, for Watching and CaughtUp, real WatchProgress ticks; none of them stores a WatchStatus, which stays derived (ADR 0005). CaughtUp is one verdict but presents as two words: a film has a single element, so its button reads **Watched** — the WatchStatus its ticks derive — while a show reads **Caught up**. The distinction is wording only; the persisted verdict and the sync payload are identical.
_Avoid_: watchlist (that is a CollectionEntry with NotStarted status), seen (that is an episode tick).
