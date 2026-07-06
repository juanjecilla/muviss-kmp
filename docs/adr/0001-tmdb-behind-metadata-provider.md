# TMDB as the primary source, behind a MetadataProvider seam

We integrate media data from TMDB, the only source at launch, but access it exclusively through a `MetadataProvider` interface (in `:core:network`) that returns our own `:models` types. This keeps TMDB's shape out of the domain and lets additional sources (TVmaze, Trakt, …) plug in later by implementing the interface and registering with `MetadataProviderRegistry` — no change to domain, data, or UI.

## Considered Options

- **Multi-source aggregation from day one** — rejected as premature; TMDB covers movies, TV, seasons and episodes with a generous free tier.
- **Trakt-centric** — rejected; ties the account model to a vendor and we are offline-first with no login.
