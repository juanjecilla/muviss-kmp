// Every string on the landing, English. es.ts must have the same shape.
//
// Paid features (sync, co-watch) are deliberately absent: the landing lists
// what everyone gets. Never call anything "premium" — see CONTEXT.md's
// Entitlement entry; the app has paid features, not a tier.
export const en = {
  meta: {
    title: "Muviss — track every movie and show you watch",
    description:
      "A private, offline-first tracker for movies and TV shows. No sign-up, no social, no ads. Open source, on Android, iOS, desktop and web.",
  },
  nav: { features: "Features", privacy: "Privacy", openSource: "Open source", github: "GitHub" },
  hero: {
    eyebrow: "Movie & TV tracker",
    title: "Know exactly where you left off.",
    lede: "Muviss keeps track of every movie and show you watch — episode by episode, offline, and only on your device. No sign-up, no feed, no ads.",
    github: "View on GitHub",
    seeFeatures: "See what it does",
    shotAlt: "A show's detail screen with its seasons and episode ticks",
  },
  spotlights: [
    {
      id: "status",
      kicker: "Automatic status",
      title: "Tick episodes. Muviss does the rest.",
      body: "You never set a status by hand. Muviss works out whether a title is Not started, Watching, Watched or Finished from the episodes you've actually seen — and whether the show is still airing.",
      shot: "library",
      alt: "The Library screen with titles grouped by watch status",
    },
    {
      id: "next",
      kicker: "Watch next & upcoming",
      title: "Always the right next episode.",
      body: "One list of the shows you're part-way through, each with its next unseen episode and a one-tap tick. Upcoming shows what airs today, this week and later — and it all works offline.",
      shot: "watch-next",
      alt: "The Watch next list with the next episode of each show",
    },
    {
      id: "triage",
      kicker: "Triage",
      title: "Fill your library in minutes.",
      body: "Go through titles one card at a time: not for me, later, watching or caught up. Muviss never asks twice about something you've decided — and if you can't decide yet, snooze it and it comes back when it's due.",
      shot: "triage",
      alt: "A triage card with the four verdict buttons",
    },
    {
      id: "stats",
      kicker: "Your stats",
      title: "See what you really watch.",
      body: "Hours watched, episodes seen, watch streaks, your favourite genres and average rating. Plus what you rewatch most — counted fairly, so a long show can't outrank a film just by being long.",
      shot: "profile-stats",
      alt: "The Profile screen with watch statistics and charts",
    },
  ],
  features: {
    title: "Everything you need to keep track",
    lede: "Everything here is free — no paywall on the basics.",
    items: [
      { icon: "search", title: "Search & discover", body: "Any movie or show from TMDB, plus For you, Popular now and browsing by genre." },
      { icon: "tv", title: "Where to watch", body: "See which streaming, rental and purchase services carry a title in your region." },
      { icon: "grid", title: "Your library", body: "Filter by status or favourites, sort by date added, rating or title." },
      { icon: "heart", title: "Favourites", body: "Mark the ones you love, independently of how far you've watched." },
      { icon: "list", title: "Custom lists", body: "“Marathon 2026”, “For a rainy day” — make as many lists as you like." },
      { icon: "check", title: "Episode tracking", body: "Per-episode ticks, season progress, and bulk marks that only tick what has actually aired." },
      { icon: "repeat", title: "Rewatch history", body: "Watched it three times? Muviss remembers every viewing and when it happened." },
      { icon: "star", title: "Ratings & notes", body: "Rate titles and keep a private note on each one." },
      { icon: "bell", title: "New-episode alerts", body: "Get notified when a new episode of a show you follow is out.", platforms: "Android · iOS" },
      { icon: "widget", title: "Home-screen widgets", body: "Your next episode on the home screen, with a one-tap tick and undo.", platforms: "Android · iOS" },
      { icon: "import", title: "Import", body: "Bring your history from Trakt, TV Time or a CSV file." },
      { icon: "export", title: "Export", body: "Your data is yours: export everything to a JSON file at any time." },
      { icon: "moon", title: "Light & dark", body: "Follows your system, or pick a theme yourself." },
      { icon: "globe", title: "English & Spanish", body: "The app speaks your language, English or Spanish. Titles, synopses and availability follow the language and country you choose." },
    ],
  },
  privacy: {
    title: "Private by design",
    lede: "Muviss is a personal tool, not a community. Your library lives in a database on your device.",
    points: [
      { title: "Offline-first", body: "Everything you've saved works without a connection." },
      { title: "No sign-up", body: "Install it and start tracking. Nothing to register." },
      { title: "No social", body: "No feeds, no followers, no comments, no public profile." },
      { title: "No ads, no analytics", body: "Only optional crash reports, which you can turn off in Settings." },
    ],
    link: "Read the privacy policy",
  },
  platforms: {
    title: "One app, everywhere you watch",
    lede: "The same Muviss on your phone, your computer and your browser.",
    comingSoon: "Coming soon",
    get: "Get it",
    items: { android: "Android", ios: "iOS", desktop: "macOS · Windows · Linux", web: "Web" },
  },
  openSource: {
    kicker: "Open source",
    title: "Built in the open.",
    body: "Muviss is MIT-licensed. One Kotlin Multiplatform and Compose Multiplatform codebase ships Android, iOS, desktop and web, and every architectural decision is written down as an ADR.",
    repo: "Browse the code",
    adrs: "Read the decisions",
    contribute: "How we work",
  },
  footer: {
    tmdb: "This product uses the TMDB API but is not endorsed or certified by TMDB.",
    justwatch: "Where-to-watch data is provided by JustWatch.",
    privacy: "Privacy policy",
    language: "Español",
    license: "MIT licensed",
  },
  shotPending: "Screenshot coming soon",
  notFound: { title: "Nothing here", body: "This page doesn't exist.", home: "Back to Muviss" },
};

export type Dictionary = typeof en;
