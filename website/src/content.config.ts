import { defineCollection } from "astro:content";
import { glob } from "astro/loaders";

// The privacy policy is rendered straight from docs/PRIVACY.md, so the page
// the stores link to can never drift from the document in the repo.
const legal = defineCollection({
  loader: glob({ pattern: "PRIVACY.md", base: "../docs" }),
});

export const collections = { legal };
