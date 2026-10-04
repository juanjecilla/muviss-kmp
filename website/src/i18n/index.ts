import { en } from "./en";
import { es } from "./es";

export type Locale = "en" | "es";

const dictionaries = { en, es };

export const t = (locale: Locale) => dictionaries[locale];

export const homePath = (locale: Locale) => (locale === "en" ? "/" : "/es/");

export const otherLocale = (locale: Locale): Locale => (locale === "en" ? "es" : "en");
