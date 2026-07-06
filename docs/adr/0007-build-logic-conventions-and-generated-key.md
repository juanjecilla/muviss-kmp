# build-logic convention plugins; generated TMDB key instead of BuildKonfig

Two build-time decisions worth recording:

**Convention plugins.** With ~30 Kotlin Multiplatform modules, per-module Gradle boilerplate is factored into an included `build-logic` build exposing `muviss.kmp.library` (targets + Android library config + namespace derived from the project path) and `muviss.kmp.compose` (adds Compose). Every core/feature module applies one of these.

**No BuildKonfig.** The TMDB API key is injected via a tiny hand-rolled Gradle task in `:core:network` that reads `TMDB_API_KEY` from `local.properties` (gitignored) or the environment and generates a `MuvissBuildConfig` constant. This avoids taking a dependency on the BuildKonfig plugin on a bleeding-edge Kotlin/AGP toolchain, and is ~15 lines we fully control.

The key ships in the client (read-only, rate-limited TMDB key — low risk). The `:server` module is kept in the repo but dormant; it is not deployed, and would only be revived to relay sync if we ever self-host instead of using Firebase/Supabase.
