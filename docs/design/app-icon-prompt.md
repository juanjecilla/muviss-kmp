# Muviss app icon — image-generation prompt (nanobanana)

Replaces the placeholder purple square with the Design System v1.0 brand
mark: a **Marquee Amber squircle with a dark play triangle**. This file is
the complete, ready-to-send prompt plus the per-store export requirements
for whoever turns the generated master into production assets.

## Concept

- Squircle (superellipse) tile filled with a 150° diagonal amber gradient:
  `#FFCB6B` (top-left) → `#E0952B` (≈70%) → `#C97A1C` (bottom-right).
- Centered dark play triangle, color `#241A04`, slightly rounded corners,
  ~50% of tile width, **optically** centered (nudged right so the triangle's
  visual mass sits centered, not its bounding box).
- Subtle inner glow near the top-left of the gradient; no text, no border,
  no drop shadow baked into the artwork.
- Flat vector style — the icon must read at 16px.

## Ready-to-send prompt

```text
A flat vector app icon on a transparent background: a rounded squircle
(superellipse, iOS-style corner smoothing) filled with a smooth diagonal
gradient at 150 degrees from warm amber #FFCB6B in the top-left through
#E0952B at 70% to deep amber #C97A1C in the bottom-right corner. Centered
inside is a single solid play-button triangle pointing right, color very
dark warm brown #241A04, with slightly rounded corners, occupying about
half the squircle's width, optically centered (shifted a few percent to
the right so it looks centered). A very subtle soft inner glow near the
top-left of the gradient. Absolutely flat design: no text, no border, no
outer drop shadow, no bevel, no noise, no skeuomorphism, no extra
elements. Clean, minimal, modern, crisp edges, high resolution, centered
composition, 1024x1024.
```

Variants to also request (same prompt, one line changed):

- **Full-bleed square** (App Store master): "…filled squircle…" → "the
  gradient fills the entire square canvas edge to edge with no squircle
  mask and no transparency" (iOS masks the corners itself).
- **Dark / mono** (themed icons, .icns/.ico dark): "squircle filled with
  flat very dark warm brown #14120E with a 1px subtle lighter border, play
  triangle in amber #FFCB6B".
- **Foreground layer only** (Android adaptive): "only the dark play
  triangle #241A04 on a fully transparent canvas, triangle at about 33% of
  canvas width, centered" (the gradient becomes the background layer, a
  plain 150° gradient fill with no mask).

## Per-format requirements

| Target | Files | Rules |
|---|---|---|
| Android adaptive | foreground + background layers, 432×432 each; legacy 512×512 for Play | Foreground: triangle only, inside the 66% safe zone (Android masks the outer ring). Background: flat gradient, full bleed. |
| iOS App Store | 1024×1024 master | Square, **no alpha channel**, full-bleed gradient — the OS applies the squircle mask. |
| macOS `.icns` | 1024 base (+512/256/128/32/16) | Pre-masked squircle **with margin** per HIG (~10% padding, artwork doesn't touch canvas edges). |
| Windows `.ico` | 256/48/32/16 bundled | Squircle pre-masked; verify the 16px render keeps the triangle legible. |
| PWA | 192×192, 512×512 + maskable variants | Maskable: keep all meaningful content inside the central 80% safe zone. |
| Notification / small (Android) | 24dp monochrome | Single-color triangle glyph; already covered by `MuvissIcons.Play`. |

## After generation — where files land

- Android: `app/androidApp/src/main/res/mipmap-*` (adaptive XML +
  layers), Play listing 512 kept in `docs/design/`.
- iOS: `app/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/`.
- Desktop packaging: `app/desktopApp` compose-desktop `nativeDistributions`
  icon config (`.icns` / `.ico`).
- Web: `app/webApp` static resources + manifest entries (192/512 +
  maskable).

Checklist before shipping: optical centering verified at 16px and 512px,
adaptive-icon safe zone tested with circle and rounded-square masks, App
Store master confirmed alpha-free (`sips -g hasAlpha`).
