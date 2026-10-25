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
computes the WCAG formula directly rather than calling Compose's `luminance()`.
The text and scrim colours are `internal` constants in `RatingBadge.kt` so the
test checks the values the badge actually uses.

Not verified: a real poster sampled from the fixtures. The ratio above is a
bound, not a measurement of any particular image; with an opaque-enough scrim
no artwork can push the text below it.
