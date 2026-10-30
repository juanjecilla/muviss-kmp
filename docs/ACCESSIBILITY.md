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

### Still open: real posters (#592)

Not verified: a real poster sampled from the fixtures. Every number above is a
synthetic backdrop, not a measurement of any particular image. With an
opaque-enough scrim no artwork can push the text below the bound, but that is
an argument, not a sample. Tracked in #592.
