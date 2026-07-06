# Source-namespaced MediaId

A `MediaId` is the triple `(source, type, externalId)`, rendered as `tmdb:tv:1399`. Namespacing by source prevents id collisions across providers and lets the same title from two sources be reconciled later via a shared anchor (`MediaAnchors.imdbId`). `SourceId` is an open value, so registering a new provider needs no schema change.

We rejected an internal opaque UUID with a side mapping table: it adds a lookup and indirection with no benefit while TMDB is the sole source, and the namespaced form is human-readable in logs, routes, and the database.
