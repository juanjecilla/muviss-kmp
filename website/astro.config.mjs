// @ts-check
import { defineConfig } from "astro/config";

// The landing for muvissapp.com, served by this repo's GitHub Pages
// (.github/workflows/deploy-pages.yml). The Wasm web app is not hosted here:
// it gets app.muvissapp.com later, because one repo's Pages site carries one
// custom domain — see docs/RELEASING.md §10.
export default defineConfig({
  site: "https://muvissapp.com",
  trailingSlash: "ignore",
  i18n: {
    defaultLocale: "en",
    locales: ["en", "es"],
    routing: { prefixDefaultLocale: false },
  },
});
