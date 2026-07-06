# Vertical slices with per-feature api/domain/data/ui modules

Each feature is a vertical slice (`:feature:<name>`) split horizontally into four Gradle modules following clean architecture:

- **`:api`** — the feature's public contract (interfaces + exported models). The only module a peer feature may depend on.
- **`:domain`** — use cases, repository interfaces, business rules. Internal.
- **`:data`** — repository implementations, data sources, Koin bindings. Internal.
- **`:ui`** — Compose screens, ViewModels, navigation. Internal.

Dependency rule: `ui → domain ← data`. Cross-feature dependencies go through the peer's `:api` only — never its `:domain`, `:data`, or `:ui` — which prevents cycles and keeps features swappable.

Shared concerns live in thin modules: `:models` (common exported data models), `:core:common`, `:core:model`, `:core:database`, `:core:network`, `:core:designsystem`. **Koin** wires the graph; each module contributes a Koin module and the app assembles them.

## Consequences

- ~24 feature modules plus core; boilerplate is contained by `build-logic` convention plugins (`muviss.kmp.library`, `muviss.kmp.compose`).
- Adding a feature = copy the search slice's four modules and register them in `settings.gradle.kts` + `AppModules`.
