// Where each download badge points. `null` renders the badge as
// "Coming soon" and not clickable — flip one to a URL the day that store
// listing goes live, nothing else on the page changes (#170).
export const links = {
  github: "https://github.com/juanjecilla/muviss-kmp",
  playStore: null as string | null,
  appStore: null as string | null,
  // Signed DMG / MSI / DEB are attached to GitHub Releases by release.yml.
  desktop: null as string | null,
  // The Wasm web app, once it is hosted at app.muvissapp.com (#167).
  web: null as string | null,
};

export const repoPath = (path: string) => `${links.github}/blob/main/${path}`;
