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

#### Measured (fixtures fetched 2026-10-28)

Source: TMDB `/3/movie/popular`, `language=en-US`, page 1, first 12 titles with
a poster, `w500` images. Files are named `<tmdb-id>.jpg`, so each row traces to
`themoviedb.org/movie/<id>`.

| TMDB id | Ink     | Lightest backdrop | Lightest : ink | Darkest backdrop | Darkest : ink |
|---------|---------|-------------------|----------------|------------------|---------------|
| 969681  | #FFCB6B | #010E18           | 13.00:1        | #060000          | 13.90:1       |
| 1058424 | #FFCB6B | #303437           | 8.38:1         | #0B0E0F          | 12.92:1       |
| 1101412 | #FFCB6B | #4B4A49           | **5.90:1**     | #3D3D3C          | 7.25:1        |
| 1148365 | #FFCB6B | #2A3B3D           | 7.82:1         | #1D363A          | 8.54:1        |
| 1232569 | #FFCB6B | #020102           | 13.90:1        | #020102          | 13.90:1       |
| 1291595 | #FFCB6B | #041014           | 12.86:1        | #000401          | 13.76:1       |
| 1368337 | #FFCB6B | #463C31           | 7.18:1         | #443A2B          | 7.43:1        |
| 1400940 | #FFCB6B | #000000           | 14.01:1        | #000000          | 14.01:1       |
| 1423191 | #FFCB6B | #090B0C           | 13.15:1        | #020303          | 13.77:1       |
| 1514026 | #FFCB6B | #121214           | 12.48:1        | #060505          | 13.58:1       |
| 1561726 | #FFCB6B | #393634           | 8.00:1         | #0A0C0F          | 13.06:1       |
| 687163  | #FFCB6B | #051218           | 12.66:1        | #020B0E          | 13.25:1       |

All twelve pass 4.5:1. The worst is 1101412 at **5.90:1**, with a lightest
backdrop of #4B4A49. That is darker than #4D4D4D, the scrim's limit over a
pure white poster, so every measured backdrop sits inside the analytic bound
(5.6:1 or better).

This is a **popular-titles sample, not a lightest-poster sample**. Nothing in
it is a near-white poster, so the bound is what covers that case, not a
measurement. A real white-background poster has not been measured.

#### How to run it

Fetch the fixtures into a folder **outside the repo**. `/poster-fixtures/` is
gitignored in case one is placed there anyway:

```bash
export TMDB_READ_TOKEN=...   # or TMDB_API_KEY=... (v3 key); neither is printed
scripts/a11y/fetch-rating-badge-posters.sh "$HOME/muviss-posters" 12
```

Then run the test with the folder's absolute path. Gradle reuses a passed test
task whose inputs did not change, and the folder is not an input, so pass
`--rerun`:

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
