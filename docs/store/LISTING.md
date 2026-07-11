# Play Store listing copy

Text only — no image assets in this repo yet (feature graphic and
screenshots are produced separately; see the checklist at the bottom).

## English

### Short description (max 80 characters)

```
Track every movie and show you watch. No accounts, no social, just tracking.
```
(77 characters)

### Full description (max 4000 characters)

```
Muviss is a simple, private tracker for movies and TV shows.

Search any title, save it to your collection, and tick off episodes as you
watch them. Muviss keeps track of what's Not Started, Watching, Watched, or
Finished — automatically, based on what you've actually seen and whether a
show is still airing.

WHAT MUVISS DOES
• Search movies and TV shows, with posters, synopses, and season/episode
  details.
• Save titles to your personal collection.
• Track progress episode by episode; movies get a simple watched toggle.
• Automatic status: Muviss derives Not Started / Watching / Watched /
  Finished from what you've watched — you never have to set it by hand.
• Mark favorites independently of watch status.
• Everything works offline once a title is saved.

WHAT MUVISS DOESN'T DO
• No accounts. No sign-up, no login, nothing tied to your identity.
• No social features — no feeds, no friends, no comments. This is a
  personal tool, not a community.
• No ads, no analytics, no tracking of you (only optional, anonymous crash
  reports if something breaks — see our privacy policy).

Your data stays on your device. Muviss is offline-first: your collection and
progress live in a local database, not in the cloud.

This product uses the TMDB API but is not endorsed or certified by TMDB.
```
(~1160 characters — well within the 4000 limit; room to grow as features
ship.)

## Español

### Descripción corta (máx. 80 caracteres)

```
Lleva el control de lo que ves. Sin cuentas, sin redes, solo seguimiento.
```
(74 caracteres)

### Descripción completa (máx. 4000 caracteres)

```
Muviss es un gestor de seguimiento simple y privado para películas y series.

Busca cualquier título, guárdalo en tu colección y marca los episodios a
medida que los ves. Muviss controla automáticamente si un título está Sin
empezar, Viéndose, Visto o Terminado, según lo que realmente hayas visto y
si la serie sigue en emisión.

QUÉ HACE MUVISS
• Busca películas y series, con carátulas, sinopsis y detalles de
  temporadas y episodios.
• Guarda títulos en tu colección personal.
• Controla el progreso episodio a episodio; las películas tienen un simple
  interruptor de visto.
• Estado automático: Muviss calcula Sin empezar / Viéndose / Visto /
  Terminado a partir de lo que has visto — nunca tienes que marcarlo a mano.
• Marca favoritos de forma independiente al estado de visionado.
• Todo funciona sin conexión una vez que guardas un título.

QUÉ NO HACE MUVISS
• Sin cuentas. Sin registro, sin inicio de sesión, nada vinculado a tu
  identidad.
• Sin funciones sociales: sin muro de actividad, sin amigos, sin
  comentarios. Es una herramienta personal, no una comunidad.
• Sin anuncios, sin analítica, sin rastreo (solo informes de errores
  anónimos y opcionales si algo falla — consulta nuestra política de
  privacidad).

Tus datos se quedan en tu dispositivo. Muviss funciona sin conexión: tu
colección y tu progreso viven en una base de datos local, no en la nube.

Este producto usa la API de TMDB pero no está avalado ni certificado por
TMDB.
```

## Screenshot checklist

Capture on a phone-size device (required) and, if time allows, a tablet
(optional but improves store presence). Suggested set, in display order:

- [ ] **Search** — search results for a popular title, showing poster grid.
- [ ] **Detail (movie)** — a movie's detail screen with synopsis + watched
      toggle.
- [ ] **Detail (TV)** — a TV show's detail screen with seasons/episodes and
      progress ticks visible.
- [ ] **Collection** — the library screen with a mix of statuses (Watching /
      Watched / Finished) visible, to show the status model at a glance.
- [ ] **Progress ("watch next")** — the progress tab showing an in-progress
      show with its next unseen episode highlighted.
- [ ] Optional: **Favorites filter** applied on the Collection screen.

Each screenshot should be captured with realistic, varied data (not an empty
state) and light theme unless/until dark theme ships (EPIC 8) and dark
screenshots are added too.

Feature graphic (1024×500) is not produced yet — tracked as store-listing
follow-up work, not blocking the first internal-track release.

## App Store (iOS) — EPIC 11 / issue #13

Same English/Spanish copy above reused verbatim where App Store Connect's
fields match Play's — App Store just slices it slightly differently.
See `docs/RELEASING.md`'s new iOS section for the archive/TestFlight/
signing steps this listing gets attached to.

### Field mapping

| App Store Connect field | Source |
| --- | --- |
| Name | `Muviss` |
| Subtitle (max 30 chars) | `Track movies & TV, privately` (29 chars) — a compressed version of the short description; App Store has no separate "short description" field the way Play does, just this + the promotional text below. |
| Promotional text (max 170 chars, editable without a new review) | First paragraph of the full description above, i.e. `Muviss is a simple, private tracker for movies and TV shows. No accounts, no social — just tracking.` (~100 chars) |
| Description (max 4000 chars) | The "Full description" text above, verbatim (same 4000-char budget as Play). |
| Keywords (max 100 chars, comma-separated, not shown to users) | `movie tracker,tv tracker,watchlist,episode tracker,watch progress,tv shows,movies` |
| What's New (per version) | Written per release once there's a changelog to summarize; not produced yet (no tagged releases exist). |
| Privacy Policy URL | Points at `docs/PRIVACY.md`'s published/hosted location (same document Play's Data Safety answers in `DATA_SAFETY.md` trace back to) — needs the doc hosted somewhere public before submission; it lives in-repo only today. |
| App Store category | Entertainment (primary); no secondary category needed. |
| Age rating | No objectionable content, no user-generated content, no gambling — the "4+" questionnaire path (no mature content declared). |

### App Store "Privacy" (Nutrition Label) questionnaire

Same answer as Play's Data Safety form (`DATA_SAFETY.md`): Muviss collects
**no data** — no accounts, no backend, no analytics/advertising SDKs. The
only thing that ever leaves the device is an optional, anonymous Sentry
crash report if `SENTRY_DSN` is configured (see `docs/RELEASING.md` item
4) — declare **Crash Data**, linked to no identity, used for App
Functionality only, matching how `DATA_SAFETY.md` frames the same fact for
Play.

### Screenshot sizes (App Store Connect requires per-device-class sets)

Reuse the same shot list/order as the Play screenshot checklist above —
just captured at App Store Connect's required sizes instead of Play's
single flexible size:

- [ ] 6.9"/6.7" display (iPhone 16 Pro Max class) — required.
- [ ] 6.5" display (iPhone 11 Pro Max/XS Max class) — required if no
      6.9"/6.7" set is uploaded for older-device fallback; safest to
      provide both.
- [ ] iPad Pro 13"/12.9" — only needed if the App Store listing claims
      iPad support (it does — `TARGETED_DEVICE_FAMILY = "1,2"` in
      `project.pbxproj`).

None of these are captured yet — same "not blocking the first internal
release" status as Play's screenshot checklist, but App Store Connect (unlike
Play's internal testing track) **will refuse to submit for review** without
at least the required iPhone set, so this blocks the App Store submission
step specifically (TestFlight internal testing does not require
screenshots — only the eventual public App Store listing does).
