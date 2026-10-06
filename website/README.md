# muvissapp.com

The Muviss landing: Astro, static, deployed to this repo's GitHub Pages by
`.github/workflows/deploy-pages.yml` (see `docs/RELEASING.md` §10).

```bash
npm install
npm run dev       # http://localhost:4321
npm run build     # → dist/
npm run preview
```

Node version is pinned in `.nvmrc`.

## Rules

- **Every free, user-visible feature is here, added in the PR that ships it**
  (`AGENTS.md`). The site deploys from `main` only, so copy merged on
  `develop` goes live when its feature is released (ADR 0025). CI's
  `landing-guard` fails a `feat` PR that changes the app but not `website/`,
  unless it is labelled `no-landing`.
- **Free features only.** Sync and co-watch are paid (they need an Entitlement:
  ADR 0018, ADR 0022) and never appear here. Say "paid", never "premium":
  `CONTEXT.md` lists "premium/pro" under _Avoid_, because the app has paid
  features, not a tier.
- **No third-party requests.** Fonts are self-hosted, and there are no
  analytics and no CDN. The page promises privacy, so it keeps it.
- **All copy lives in `src/i18n/en.ts` and `src/i18n/es.ts`.** `es` is typed
  as `en`'s `Dictionary`, so an editor flags drift. Note that `npm run build`
  does not type-check (there is no `astro check` wired up).
- **`/privacy/` is `docs/PRIVACY.md`**, loaded through `src/content.config.ts`.
  Edit the policy there, never here. It is the URL the store listings use.
- **Colours mirror `MuvissPalette`**
  (`core/designsystem/.../theme/Color.kt`) in `src/styles/global.css`. The
  font is the app's bundled Schibsted Grotesk.
- **Download badges come from `src/config/links.ts`.** `null` means
  "Coming soon" (#170).

## Screenshots (#168)

These are captured by hand from a real build. Put them in
`public/screenshots/<name>-light.png` and `<name>-dark.png`, portrait
1080×2400. A missing file renders a branded placeholder, and if only one theme
exists it is used for both.

| name | screen |
|---|---|
| `detail-seasons` | A show's detail, seasons expanded with some episodes ticked (hero) |
| `library` | Library with a status filter |
| `watch-next` | Progress → Watch next |
| `triage` | A triage card with the verdict buttons |
| `profile-stats` | Profile with stats and charts |

None of the screenshots may show sync or co-watch UI.

`public/og.png` is generated from `assets/og.svg`:
`rsvg-convert -w 1200 -h 630 assets/og.svg -o public/og.png`.
