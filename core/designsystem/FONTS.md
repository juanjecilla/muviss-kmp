# Bundled fonts

`src/commonMain/composeResources/font/` contains **Schibsted Grotesk**
(weights 400/500/600/700), the app-wide type family (see the Muviss Design
System, Typography section).

- Source: Google Fonts static instances, family `Schibsted Grotesk` v7
  (upstream: https://github.com/schibsted/schibsted-grotesk)
- License: SIL Open Font License 1.1 — full text in [FONTS-OFL.txt](FONTS-OFL.txt).
  Free to bundle and redistribute; also listed in the in-app
  Settings > About > Licenses screen (`OssLicenses.kt`).
- The variable font was not used on purpose: static instances rasterize more
  predictably on the Skiko/wasm targets.
