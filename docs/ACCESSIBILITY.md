# Accessibility notes

How specific accessibility decisions were checked. Each entry records the
numbers, so a future change can be re-verified rather than re-argued.

## Contrast: rating badge on posters (#458)

`RatingBadge` (`core/designsystem/.../component/RatingBadge.kt`) draws amber
text `#FFCB6B` on a black scrim over a poster. The poster is not under our
control, so the check uses the **worst case**: a white poster.

Method (WCAG 2.x, relative luminance, sRGB linearised per channel):

- Amber `#FFCB6B`: L = 0.2126·R + 0.7152·G + 0.0722·B = **0.6503**.
- Scrim composited over white is `255 × (1 − alpha)` on each channel.
- Contrast = (L_amber + 0.05) / (L_bg + 0.05).

| Scrim (black alpha) | Over white → grey | Contrast vs amber |
|---------------------|-------------------|-------------------|
| 55% (before)        | #737373 (115)     | **3.16:1 (fails 4.5:1)** |
| 64%                 | #5B5B5B (91)      | 4.53:1 (floor)    |
| **70% (now)**       | #4D4D4D (77)      | **5.64:1**        |
| 100%                | #000000           | 14.0:1            |

WCAG AA needs 4.5:1 for the 11 sp label and 3:1 for the star icon (SC 1.4.11).
The scrim is theme-independent (the badge sits on the poster, not on the theme
surface), so light and dark produce the same ratio.

The numbers are asserted in `RatingBadgeContrastTest` (`commonTest`), which
computes the WCAG formula directly through the shared helpers in
`WcagContrast.kt`, rather than calling Compose's `luminance()`. The text and
scrim colours are `internal` constants in `RatingBadge.kt` so the test checks
the values the badge actually uses. There is no `contrast-check` tool in the
repo; the check is this unit test.

### Measured from rendered pixels (#469)

The formula above is arithmetic on the constants. `RatingBadgeRenderedContrastTest`
(`jvmTest`) renders the badge over synthetic backdrops, captures the pill, and
measures the pixels. Text ink is the brightest pixel of the pill's central
band; the background is the lightest tone covering at least 5% of that band,
so anti-aliasing does not count as background. Ratio is WCAG over those two.

| Backdrop (synthetic)           | Rendered background | Measured ink : background |
|--------------------------------|---------------------|---------------------------|
| White (the lightest)           | #4C4C4C (76)        | **5.73:1**                |
| High-frequency two-tone checker (2 px cells, white + black) | #4C4C4C under the white cells | **5.73:1** |
| Mid-grey #808080               | #262626 (38)        | 10.09:1                   |
| Black (the darkest)            | #000000             | 14.01:1                   |

The ink measured as `#FFCB6B` in every case. The checker's dark cells
composite to #000000 and are not the worst case, so the white-cell figure is
the one that counts. The rendered white-backdrop background is 76 (#4C4C4C),
one step darker than the 77 in the formula table above, because the 70%
alpha quantises to 179/255 when Skia composites it. The rendered figure is the
more favourable one, so the formula table stays a conservative bound.

Regression check: a temporary 55% scrim renders the white backdrop at #737373
and measures **3.16:1**, which fails. The same change fails the four
`RatingBadgeGoldenTest` goldens on the palette check. Both guards were
confirmed to fire before the scrim was restored.

### Golden

`RatingBadgeGoldenTest` records the badge over the white and black backdrops,
in light and dark themes (`rating-badge-{white,black}-{light,dark}.png`). The
goldens were recorded on macOS. Rendered on Linux (FreeType, Ubuntu 24.04
container, `-PgoldenReport`) they move 1.16% (black backdrop) and 0.94% (white),
all glyph edges, with no palette colour lost or gained; the tolerance is 1.5%.

### Real posters (#592)

Every number above is a synthetic backdrop. Real artwork is measured by
`RatingBadgePosterFixturesTest` (`jvmTest`), which draws `RatingBadge` at the
card's `TopEnd` corner over each image in a folder and samples it the same way.
It is opt-in and skipped in normal runs, because posters cannot be committed
(licence and size).

Two backdrop figures per poster, both against the amber ink:

- **Lightest** is the worst case for the text, and the one the test asserts
  against 4.5:1.
- **Darkest** is reported only.

Sampling on photographs uses a coarser rule than the synthetic tests. Each
channel is quantised to 32 levels and a backdrop tone must cover 2% of the
pill's band, because a photograph never repeats an exact colour. Anti-aliasing
spreads over many buckets and rarely clears that bar, which is why this works.
The analytic bound still holds: over a pure white poster the scrim gives
#4D4D4D, which is 5.6:1, so no artwork can read lighter than that behind the
70% scrim.

#### Measured (2026-10-28)

Pool: 116 distinct posters, the first 60 titles with a poster from TMDB
`/3/movie/popular` and the first 60 from `/3/movie/top_rated` (pages 1 to 3 of
each), `w500`, `language=en-US`. Files are named `<tmdb-id>.jpg`, so a row
traces to `themoviedb.org/movie/<id>`.

Across the pool the lowest lightest-backdrop ratio is 5.73:1, the median is
9.85:1 and the highest is 14.01:1. **None of the 116 falls below 4.5:1.**

**Lightest set**: the five lowest lightest-backdrop ratios. They were chosen
from the ranking, not by whole-poster brightness, because only the region under
the badge is sampled. The top two sit at #4C4C4C, the rendered white case from
the synthetic test (5.73:1). No poster in the pool reads lighter than a white
backdrop behind the 70% scrim.

| TMDB id | Ink | Lightest backdrop | Lightest : ink | Darkest backdrop | Darkest : ink |
|---------|-----|-------------------|----------------|------------------|---------------|
| 14537 | #FFCB6B | #4C4C4C | 5.73:1 | #4C4C4C | 5.73:1 |
| 311 | #FFCB6B | #4C4C4C | 5.73:1 | #4C4C4C | 5.73:1 |
| 1712451 | #FFCB6B | #4C4C4A | 5.74:1 | #020302 | 13.78:1 |
| 510 | #FFCB6B | #4B4B4A | 5.83:1 | #010102 | 13.92:1 |
| 1101412 | #FFCB6B | #4B4A49 | 5.90:1 | #3D3D3C | 7.25:1 |

**Darkest set**: the twelve posters whose backdrop under the badge is pure black,
the ceiling of 14.01:1. All twelve tie, so they are listed in full.

| TMDB id | Ink | Lightest backdrop | Lightest : ink | Darkest backdrop | Darkest : ink |
|---------|-----|-------------------|----------------|------------------|---------------|
| 11216 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 12477 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 129 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 1339713 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 1400940 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 238 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 240 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 244786 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 423 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 550 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 696374 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 769 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |

<details>
<summary>All 116 posters, sorted by lightest-backdrop ratio</summary>

| TMDB id | Ink | Lightest backdrop | Lightest : ink | Darkest backdrop | Darkest : ink |
|---------|-----|-------------------|----------------|------------------|---------------|
| 14537 | #FFCB6B | #4C4C4C | 5.73:1 | #4C4C4C | 5.73:1 |
| 311 | #FFCB6B | #4C4C4C | 5.73:1 | #4C4C4C | 5.73:1 |
| 1712451 | #FFCB6B | #4C4C4A | 5.74:1 | #020302 | 13.78:1 |
| 510 | #FFCB6B | #4B4B4A | 5.83:1 | #010102 | 13.92:1 |
| 1101412 | #FFCB6B | #4B4A49 | 5.90:1 | #3D3D3C | 7.25:1 |
| 1275779 | #FFCB6B | #494A48 | 5.94:1 | #373D3B | 7.40:1 |
| 637 | #FFCB6B | #4C4949 | 5.94:1 | #0F0C09 | 13.01:1 |
| 936075 | #FFCB6B | #4B4943 | 6.00:1 | #494335 | 6.55:1 |
| 278 | #FFCB6B | #4C4844 | 6.04:1 | #493E2A | 6.98:1 |
| 429 | #FFCB6B | #474748 | 6.19:1 | #444545 | 6.42:1 |
| 1315772 | #FFCB6B | #4C4441 | 6.34:1 | #4B1E03 | 9.42:1 |
| 1433583 | #FFCB6B | #46463C | 6.36:1 | #46463C | 6.36:1 |
| 1375646 | #FFCB6B | #40464A | 6.39:1 | #3F464A | 6.41:1 |
| 255709 | #FFCB6B | #444545 | 6.42:1 | #060705 | 13.46:1 |
| 680 | #FFCB6B | #4C4500 | 6.49:1 | #030200 | 13.83:1 |
| 1288445 | #FFCB6B | #434344 | 6.59:1 | #242425 | 10.34:1 |
| 755898 | #FFCB6B | #444241 | 6.67:1 | #030303 | 13.76:1 |
| 389 | #FFCB6B | #4C4214 | 6.68:1 | #1D0A03 | 12.77:1 |
| 770156 | #FFCB6B | #32454B | 6.71:1 | #0E3943 | 8.31:1 |
| 1294189 | #FFCB6B | #42413B | 6.83:1 | #040605 | 13.55:1 |
| 1311031 | #FFCB6B | #33434B | 6.85:1 | #2B3D42 | 7.58:1 |
| 1108427 | #FFCB6B | #3A4244 | 6.86:1 | #1B2732 | 10.13:1 |
| 12493 | #FFCB6B | #48400E | 6.95:1 | #000000 | 14.01:1 |
| 1084244 | #FFCB6B | #453E28 | 7.10:1 | #261B1C | 11.15:1 |
| 1368337 | #FFCB6B | #463C31 | 7.18:1 | #443A2B | 7.43:1 |
| 598 | #FFCB6B | #483A0D | 7.42:1 | #473506 | 7.87:1 |
| 980431 | #FFCB6B | #4B3630 | 7.49:1 | #02070F | 13.47:1 |
| 1377237 | #FFCB6B | #3F3A34 | 7.51:1 | #0D0504 | 13.46:1 |
| 13 | #FFCB6B | #313B44 | 7.61:1 | #263441 | 8.49:1 |
| 378064 | #FFCB6B | #223D49 | 7.65:1 | #152415 | 10.82:1 |
| 1232449 | #FFCB6B | #323A40 | 7.72:1 | #1E313C | 8.98:1 |
| 1148365 | #FFCB6B | #2A3B3D | 7.82:1 | #1D363A | 8.54:1 |
| 496243 | #FFCB6B | #2B3B39 | 7.84:1 | #060A09 | 13.28:1 |
| 569094 | #FFCB6B | #4C3307 | 7.85:1 | #120D24 | 12.63:1 |
| 19404 | #FFCB6B | #4C3300 | 7.87:1 | #4C3300 | 7.87:1 |
| 1561726 | #FFCB6B | #393634 | 8.00:1 | #0A0C0F | 13.06:1 |
| 1657630 | #FFCB6B | #323536 | 8.25:1 | #030709 | 13.49:1 |
| 1058424 | #FFCB6B | #303437 | 8.38:1 | #0B0E0F | 12.92:1 |
| 4935 | #FFCB6B | #243443 | 8.50:1 | #05060A | 13.51:1 |
| 372058 | #FFCB6B | #133544 | 8.64:1 | #061B32 | 11.57:1 |
| 1384216 | #FFCB6B | #2D3031 | 8.87:1 | #262A2C | 9.66:1 |
| 791523 | #FFCB6B | #263039 | 8.95:1 | #122435 | 10.54:1 |
| 1632181 | #FFCB6B | #1E3040 | 9.02:1 | #15273A | 10.13:1 |
| 1302904 | #FFCB6B | #3B2B1D | 9.04:1 | #010101 | 13.92:1 |
| 424 | #FFCB6B | #2E2E2E | 9.06:1 | #000000 | 14.01:1 |
| 1255833 | #FFCB6B | #04323D | 9.16:1 | #021524 | 12.34:1 |
| 1780005 | #FFCB6B | #242E31 | 9.27:1 | #020B0F | 13.25:1 |
| 1228834 | #FFCB6B | #312B21 | 9.35:1 | #140D08 | 12.84:1 |
| 693134 | #FFCB6B | #45230A | 9.35:1 | #150A0B | 12.97:1 |
| 1383731 | #FFCB6B | #2C2B2A | 9.43:1 | #0F1213 | 12.55:1 |
| 1181678 | #FFCB6B | #362902 | 9.50:1 | #261401 | 11.84:1 |
| 667257 | #FFCB6B | #102D39 | 9.62:1 | #041E2B | 11.43:1 |
| 620249 | #FFCB6B | #222A35 | 9.66:1 | #06172B | 12.02:1 |
| 354912 | #FFCB6B | #302437 | 9.77:1 | #150A0C | 12.96:1 |
| 1204680 | #FFCB6B | #2A2825 | 9.80:1 | #1F2424 | 10.48:1 |
| 1248832 | #FFCB6B | #481A09 | 9.81:1 | #471909 | 9.93:1 |
| 1007757 | #FFCB6B | #252924 | 9.85:1 | #0C120F | 12.63:1 |
| 1621552 | #FFCB6B | #21292E | 9.85:1 | #120A07 | 13.06:1 |
| 1330021 | #FFCB6B | #32231B | 10.06:1 | #210F09 | 12.32:1 |
| 1503762 | #FFCB6B | #2A2421 | 10.20:1 | #231E1B | 11.00:1 |
| 1465063 | #FFCB6B | #242139 | 10.36:1 | #060A0C | 13.26:1 |
| 1083381 | #FFCB6B | #29240A | 10.37:1 | #000001 | 14.00:1 |
| 154200 | #FFCB6B | #0D2438 | 10.56:1 | #0C2437 | 10.59:1 |
| 1734379 | #FFCB6B | #1B2234 | 10.57:1 | #04070C | 13.46:1 |
| 1101383 | #FFCB6B | #162330 | 10.63:1 | #0F1C2C | 11.45:1 |
| 155 | #FFCB6B | #11222A | 10.89:1 | #03050A | 13.60:1 |
| 27205 | #FFCB6B | #410D06 | 10.90:1 | #010000 | 13.99:1 |
| 1187326 | #FFCB6B | #002134 | 11.05:1 | #002134 | 11.05:1 |
| 40096 | #FFCB6B | #221E18 | 11.06:1 | #030404 | 13.69:1 |
| 1765072 | #FFCB6B | #1B1D19 | 11.33:1 | #0E0D09 | 12.97:1 |
| 1218925 | #FFCB6B | #400206 | 11.39:1 | #2E0001 | 12.54:1 |
| 1375161 | #FFCB6B | #31130D | 11.39:1 | #0D0506 | 13.45:1 |
| 1153576 | #FFCB6B | #400200 | 11.42:1 | #150006 | 13.54:1 |
| 346 | #FFCB6B | #1B1A19 | 11.59:1 | #040403 | 13.68:1 |
| 299536 | #FFCB6B | #101A27 | 11.68:1 | #01050F | 13.60:1 |
| 1003596 | #FFCB6B | #0A1C1A | 11.73:1 | #000700 | 13.59:1 |
| 1263337 | #FFCB6B | #14181B | 11.91:1 | #060C0D | 13.14:1 |
| 157336 | #FFCB6B | #13181C | 11.92:1 | #070C11 | 13.09:1 |
| 1236045 | #FFCB6B | #22140D | 11.93:1 | #0B0705 | 13.38:1 |
| 1630423 | #FFCB6B | #1B140E | 12.15:1 | #060504 | 13.58:1 |
| 1400837 | #FFCB6B | #340101 | 12.17:1 | #0B0000 | 13.81:1 |
| 1514026 | #FFCB6B | #121214 | 12.48:1 | #060505 | 13.58:1 |
| 14836 | #FFCB6B | #09111F | 12.60:1 | #020304 | 13.77:1 |
| 687163 | #FFCB6B | #051218 | 12.66:1 | #020B0E | 13.25:1 |
| 1792914 | #FFCB6B | #0F1012 | 12.70:1 | #060705 | 13.46:1 |
| 807 | #FFCB6B | #120E0D | 12.80:1 | #030505 | 13.63:1 |
| 1291595 | #FFCB6B | #041014 | 12.86:1 | #000401 | 13.76:1 |
| 299534 | #FFCB6B | #0C0B1D | 12.95:1 | #000001 | 14.00:1 |
| 1891 | #FFCB6B | #110C0A | 12.96:1 | #060504 | 13.58:1 |
| 969681 | #FFCB6B | #010E18 | 13.00:1 | #060000 | 13.90:1 |
| 1560520 | #FFCB6B | #070B12 | 13.14:1 | #000105 | 13.92:1 |
| 1423191 | #FFCB6B | #090B0C | 13.15:1 | #020303 | 13.77:1 |
| 324857 | #FFCB6B | #080A15 | 13.16:1 | #040407 | 13.66:1 |
| 539 | #FFCB6B | #040A13 | 13.24:1 | #02050D | 13.60:1 |
| 120 | #FFCB6B | #0A0904 | 13.29:1 | #020200 | 13.85:1 |
| 1476746 | #FFCB6B | #00090C | 13.41:1 | #000406 | 13.73:1 |
| 1284046 | #FFCB6B | #08060A | 13.46:1 | #070306 | 13.67:1 |
| 1283515 | #FFCB6B | #050608 | 13.52:1 | #040607 | 13.54:1 |
| 1339274 | #FFCB6B | #030609 | 13.55:1 | #020406 | 13.70:1 |
| 497 | #FFCB6B | #040203 | 13.80:1 | #040203 | 13.80:1 |
| 9479 | #FFCB6B | #030101 | 13.89:1 | #030101 | 13.89:1 |
| 1232569 | #FFCB6B | #020102 | 13.90:1 | #020102 | 13.90:1 |
| 122 | #FFCB6B | #010102 | 13.92:1 | #010102 | 13.92:1 |
| 121 | #FFCB6B | #030000 | 13.95:1 | #030000 | 13.95:1 |
| 11216 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 12477 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 129 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 1339713 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 1400940 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 238 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 240 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 244786 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 423 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 550 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 696374 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |
| 769 | #FFCB6B | #000000 | 14.01:1 | #000000 | 14.01:1 |

</details>

#### How to run it

Fetch the fixtures into a folder **outside the repo**. `/poster-fixtures/` is
gitignored in case one is placed there anyway:

```bash
export TMDB_READ_TOKEN=...   # or TMDB_API_KEY=... (v3 key); neither is printed
scripts/a11y/fetch-rating-badge-posters.sh "$HOME/muviss-posters" 60 popular
scripts/a11y/fetch-rating-badge-posters.sh "$HOME/muviss-posters" 60 top_rated
```

Then run the test with the folder's absolute path. **Always pass `--rerun`.**
Gradle does not treat the variable as an input. After a run without it, a run
with it reports `:core:designsystem:jvmTest UP-TO-DATE`, the poster test does
not run, and the build stays green. Checked on 2026-10-28: a skipped run, then
a run with the variable and no `--rerun`, left the report at `skipped="1"`.

```bash
MUVISS_POSTER_DIR="$HOME/muviss-posters" \
  ./gradlew :core:designsystem:jvmTest --tests '*RatingBadgePosterFixturesTest*' --rerun
```

The per-poster table is in the test's standard output, in
`core/designsystem/build/test-results/jvmTest/TEST-*RatingBadgePosterFixturesTest*.xml`.
Without `MUVISS_POSTER_DIR` the test is skipped. A set path with an empty or
missing folder fails rather than passing.

The gate was checked against a white poster with a temporary 55% scrim: the
lightest backdrop measured **#737373 at 3.16:1** and the test failed, the same
figure as the synthetic check above. The scrim was restored to 70% afterwards.
