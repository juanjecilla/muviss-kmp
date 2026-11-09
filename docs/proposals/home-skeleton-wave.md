# Home: a skeleton that matches, and a wave that travels

Issue #723. "Home" is Discover (`SearchRoute`).

## Problem
`LoadingSkeletonGrid` is a 12-poster grid with no headers; the loaded screen is a column (intro card, Triage entry, What's next, muted chip, For you, genre chips, Popular movies, Popular TV). The page re-flows when data lands. `Modifier.shimmer()` sweeps each item on its own.

## Proposal
1. **Same shape.** "For you" is hidden once loaded when the library has no recommendation seeds (`SearchViewModel`, `SearchScreen` drops empty carousels), so a skeleton row for it would vanish and shift everything below. Seeds are known locally before any network call: show the For you skeleton only when the library has at least one seed, otherwise omit it.
    Local parts (intro, Triage entry, What's next, muted chip) need no network and render for real. Network sections render skeleton rows under their real headers, each with its **own** placeholder shape: poster cards (110 dp `PosterCard`) for For you and the Popular carousels, and chip-sized placeholders for the movie and TV genre sections, which load from the network but render `GenreChipRow` chips, not posters. Counts that fit the width, so nothing moves on load.
2. **A wave.** One gradient brush positioned in window coordinates, animated across the root, so the highlight reaches each skeleton in turn. Items read their offset from `LocalShimmer` rather than each owning an animation, which also makes it one animation, not twelve.
3. **Standalone library.** `:lib:shimmer`: Compose only, no `com.codingpit.muviss` import. API: a `ShimmerHost` (provides the clock and angle), `Modifier.shimmer(...)`, colors/duration/direction as parameters, a reduced-motion flag as a plain boolean. `:core:designsystem` keeps `PosterSkeleton`/`TextLineSkeleton`/`RowSkeleton` and feeds them Muviss colors and the reduce-motion switches (#394, EPIC 52): reduced motion → a static fill.

## Check before building
- `settings.gradle.kts`'s module loop and `verifyModuleGraph` assume `feature/`, `core/`, `app/`; confirm a `lib/` prefix is accepted, or extend the rule.
- `muviss.kmp.compose` sets up Compose Resources and `androidResources`; the library likely wants `muviss.kmp.library` + Compose plugin only.
- Web: a full-screen animated brush on canvas; measure.
- Offscreen compositing on Android for `graphicsLayer` brushes.

## Corner cases
Cache hit (see Discover cache) must not flash a skeleton; wide screens (grid vs. rows); right-to-left (wave direction flips); large font scale (header heights); TalkBack announces "Loading", not 40 skeleton nodes; rotate mid-animation keeps phase.

## Tests
Golden light/dark with a paused `mainClock`; wave position at two clock times differs and is continuous across two siblings; reduce-motion is static; a guard that the lib has no Muviss imports; `ShimmerSweepTest` migrates.
