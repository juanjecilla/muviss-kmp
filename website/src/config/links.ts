// Where each download badge points. `null` renders the badge as
// "Coming soon" and not clickable — flip one to a URL the day that store
// listing goes live, nothing else on the page changes (#170).
export const links = {
  github: "https://github.com/juanjecilla/muviss-kmp",
  playStore: "https://play.google.com/store/apps/details?id=com.codingpit.muviss" as string | null,
  appStore: null as string | null,
  // Signed DMG / MSI / DEB are attached to GitHub Releases by release.yml.
  desktop: null as string | null,
  // The Wasm web app, once it is hosted at app.muvissapp.com (#167).
  web: null as string | null,
  // The two hosted forms the app's Settings rows open (EPIC 53, ADR 0034). Public by design: they are
  // links to a form, not a way into anything.
  featureRequest: "https://tally.so/r/bZVJMg",
  bugReport: "https://tally.so/r/LD1rzy",
  // Interim contact until support@muvissapp.com exists (#438). One place, so swapping it is one edit.
  supportEmail: "codingpit@gmail.com",
};

export const issuesUrl = `${links.github}/issues`;

export const repoPath = (path: string) => `${links.github}/blob/main/${path}`;
