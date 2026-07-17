# Design brief prompt — Muviss visual & UX design, all platforms

> Copy everything below into a design-focused Claude session (ideally one with design/artifact skills). It contains full product context; no repo access needed, but link the repo if available.

---

You are designing the complete visual identity and UX for **Muviss**, a movie & TV tracker built with Kotlin Multiplatform + Compose Multiplatform. One shared Compose UI codebase renders on **Android (primary), iOS, Desktop (macOS/Windows/Linux), and Web (Wasm canvas)**. The app is feature-complete and functional but visually utilitarian — default Material 3 with no brand identity. Your job: a complete, implementation-ready design system and per-screen design for every platform.

## Product context

- **Positioning**: personal, offline-first tracker — think TV Time's tracking loop without any social layer. No comments, no community, no sharing. The user's library, progress, and stats are the whole product.
- **Tone**: personal media diary. Calm, content-forward (posters are the hero imagery), fast to tick an episode.
- **Existing brand**: none. Current app icon is a placeholder (purple rounded square + white play triangle). Naming: "Muviss". You may propose the brand identity (logo direction, palette, app icon for all stores) — flag anything that needs a human designer's final pass.

## Technical constraints (hard)

1. **One shared Compose Multiplatform UI** — no per-platform UI rewrites. Platform adaptation happens via layout logic (window size classes), not separate native UIs. Design must be expressible in Compose + Material 3 components.
2. **Material 3 is the component base**. Theming via `MaterialTheme` (color scheme, typography, shapes). Light / dark / system modes already exist as a user setting — every screen must be designed for both.
3. **Icons are hand-bundled `ImageVector`s** (`MuvissIcons` in `:core:designsystem`) — the material-icons artifact is unavailable. Every icon you spec must be listed so vectors can be bundled; prefer a small, consistent set.
4. **Web renders to a canvas** (no DOM styling); design responsive behavior via breakpoints/window-size classes, not CSS.
5. **No new third-party UI dependencies.** Charts are hand-drawn Compose canvas (bar + donut exist).
6. Poster/still/logo imagery comes from TMDB (2:3 posters, 16:9 stills, provider logos). TMDB attribution + JustWatch attribution (where-to-watch section) are legally required and must have a designed placement.

## Current information architecture (all implemented — design for exactly this, propose IA changes separately if justified)

**5 bottom-nav tabs (Android/iOS) — adapt for desktop/web:**

1. **Search / Discover** — empty state = discover browse: "For you" carousel (recommendation-seeded), "Popular now" carousels (movies + TV), genre chip rows → genre drill-down grid with infinite scroll. Typing = debounced search results grid with pagination.
2. **Library (Collection)** — segmented: **Library** (filter tabs: Not started / Watching / Watched / Finished / Favorites; sort: Recently added / Rating / Title; poster grid with rating badges) and **Lists** (user lists with counts, create/rename/delete, list contents grid).
3. **Progress** — segmented: **Watch Next** (currently-watching shows, next unseen episode, one-tap tick) and **Upcoming** (agenda of future air dates grouped Today / This week / Later).
4. **Profile** — avatar (6 color+initial presets) + editable name; stats: status breakdown bar chart, episodes seen, hours watched, genre donut, watch streaks, avg rating; sync account section (email OTP sign-in, last-synced, Sync now).
5. **Settings** — theme, TMDB language/region, notifications toggle, import (Trakt/TV Time/CSV: pick → preview counts → progress → summary with unresolved list), export, About (version, TMDB attribution, OSS licenses list).

**Detail screen** (pushed from anywhere, recursive): backdrop/poster, metadata, add-to-library / favorite / mute-notifications / add-to-list buttons, 10-star rating (tap-again-to-clear) + note editor, movie watched toggle OR season list with per-episode checkmarks + season progress bars + "mark previous seen", where-to-watch rows (flatrate/rent/buy + JustWatch attribution), "More like this" carousel.

**Dialogs/sheets**: add-to-list (checkmarks + inline create), note editor, list create/rename, sign-in email + OTP, import file preview.

**System surfaces**: Android/iOS notifications ("S02E05 of X is out" + batched summary), PWA install icons, desktop window (min 480×360, resizable), app icons for Play Store / App Store (1024px, no alpha!) / desktop (.icns/.ico) / PWA (192/512).

## Deliverables requested (in order)

1. **Brand foundation**: name treatment/logotype direction, app icon concept (all store formats), color story rationale.
2. **Design tokens, implementation-ready**: full Material 3 color scheme for light AND dark (all ~30 M3 color roles as hex), typography scale mapped to M3 roles (with font fallback strategy — bundled font vs platform default, note licensing), shape scale, spacing scale, elevation usage rules. Deliver as a Kotlin-ready token table.
3. **Component inventory**: every reusable component (poster card variants, episode row, carousel section header, stat tile, chart styles, rating row, empty/error/loading states, segmented switches, badges) with states, sizes, and M3 component mapping.
4. **Per-screen specs** for all screens above: layout at compact width (phones), medium (tablets/small desktop), expanded (desktop/web wide). Nav adaptation: bottom bar (compact) → nav rail (medium) → rail or drawer (expanded) — recommend one. Wireframe-level mockups (image, HTML mockup, or precise ASCII) plus written spec per screen.
5. **Motion**: transition patterns (tab switch, detail push, tick feedback, pull-to-refresh) within Compose's default animation toolkit.
6. **Accessibility**: contrast-verified palette (WCAG AA on all token pairings — show the math), touch target minimums, dynamic type behavior, screen-reader labeling conventions for the tick/rating interactions.
7. **Iconography**: complete named icon list (nav ×5, actions: favorite, add, tick, mute, list, sort, filter, import/export, sync, back, close, star, calendar…) with style rules (stroke weight, grid, corner radius) so vectors can be drawn consistently.
8. **Prioritized rollout plan**: order the work by user-visible impact so it can be implemented incrementally (e.g., tokens first, then Detail + Library, then charts polish).

## Ground rules

- Every choice must be implementable by a developer in Compose without guessing — when in doubt, over-specify (dp values, hex, font sizes, weights).
- Both themes are first-class; never design light-only.
- Posters are the color in the app — the chrome should recede. Justify any saturated brand color usage against that.
- No social/engagement patterns (no likes, streaks are private stats, no gamification pressure).
- Call out explicitly anything that cannot be achieved in shared Compose and would need per-platform work, so it can be costed separately.
