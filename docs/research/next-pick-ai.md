# Next Pick: could a model do the ranking?

Status: **research half of #404 done (2026-10-27); no device measurement was done.** Every performance figure below is a number a vendor or model-card publisher printed, and is labelled that way. Estimates are arithmetic on those published numbers and are labelled as estimates. The measurement that #404 still needs is filed as #594, and the seam amendment as #595. ADR 0032 is why v1 ships without any of this.

All web sources were retrieved **2026-10-27**. Source numbers are in the list at the end. `[UNVERIFIED]` marks a claim the research could not confirm from a primary source.

## Verdict

- **v1 stays rules-only** (ADR 0032). Nothing here changes that.
- **On-device Gemma 4 is the only option worth measuring.** Its licence is now Apache-2.0 (resolved below), LiteRT-LM has a stable Kotlin API for Android and JVM desktop, and the published numbers are fast enough to be worth a test. Its real blocker is delivery: the E2B model file is about 2.6 GB, more than one Play asset pack or the base module can hold. Whether split asset packs can carry it, or whether Android AI Core makes our own copy unnecessary on supported phones, is open (see Delivery).
- **Web is rules-only.** LiteRT-LM's web API is an early preview in JavaScript, not Kotlin/Wasm.
- **Remote recommendation service: reject for v1.** It needs data we do not have (other users), a sign-in path we do not have for free users, and a privacy-policy change, and it conflicts with ADR 0032 (free, offline) and ADR 0019 (paid gate).
- **Cloud LLM: reject for v1.** A built-in key is a bill we pay and an extractable secret (ADR 0027 and ADR 0026). A user-supplied key is possible later, but it is a new consent flow. Per-session cost is tiny, so cost is not the reason.
- **The ranker seam is sufficient in shape and insufficient in enforcement.** Three amendments are needed before any model ranker lands (#595).

## What a model would add

Rules map answers to TMDB filters and score by genre overlap, ratings and favorites. A model could do three things rules cannot: read a free-text request ("something like Severance but warmer"), weigh a title's overview against the user's history, and write a better "because..." line. It cannot add data we do not have: it would still need candidates from our backlog and TMDB.

## Repo facts this depends on

- Android `minSdk` is 24 (`gradle/libs.versions.toml`). The iOS deployment target is **18.0** (`app/iosApp/iosApp.xcodeproj/project.pbxproj`).
- The licensee allow-list is `Apache-2.0`, `MIT`, and `BSD-3-Clause` (`app/androidApp/build.gradle.kts`, lines 35 to 42). It checks Gradle dependencies only. Downloaded model weights are not Gradle dependencies, so it does not cover them.
- ADR 0019 is accepted but not implemented. Its entitlement webhook would be the project's first server-side code.
- `docs/PRIVACY.md` lists every network call the app makes. A new service would need an entry there and a revised statement about having no server of its own.

## On-device: per-target feasibility

Runtime status comes from the LiteRT-LM overview [S1] and the MediaPipe LLM Inference page [S2]. Model sizes and published performance come from the LiteRT-LM model cards [S3, S7]. These are vendor numbers, not ours.

| Target | Runtime and status | Model and file size | Published performance (vendor, not measured by us) | Verdict |
|---|---|---|---|---|
| **Android** | LiteRT-LM Kotlin: stable [S1]. MediaPipe LLM Inference: maintenance-only, "New features and optimizations will be focused on LiteRT-LM" [S2] | Gemma 4 E2B `.litertlm`: 2,583 MB CPU/GPU, 2,722 MB NPU variant [S3]. Gemma 3 1B int4 `.litertlm`: about 529 MB [S7] | Galaxy S26 Ultra, Gemma 4 E2B: GPU decode 52.1 tok/s, time to first token 0.3 s; CPU decode 46.9 tok/s, first token 1.8 s [S3] | **Viable to measure.** Size is the blocker (see Delivery). Minimum device for the GPU path is not stated [UNVERIFIED]. The same card says that on supported devices Gemma 4 is available through **Android AI Core as Gemini Nano**, "the recommended path for production applications" [S3]: no model file of ours to ship. Which devices qualify, how an app reaches the model, and whether it accepts our prompt were not researched [UNVERIFIED], so AI Core is the first thing #594 checks and LiteRT-LM stays the fallback for every other phone |
| **iOS** | LiteRT-LM Swift: early preview [S1]. MediaPipe: maintenance-only [S2] | Same Gemma 4 E2B file [S3] | iPhone 17 Pro, Gemma 4 E2B: GPU decode 56.5 tok/s, first token 0.3 s; CPU decode 25.0 tok/s [S3] | Viable, but needs a Swift bridge on an early-preview API, and the iOS app must be built with it. Apple's own on-device model is a separate option (below) |
| **Desktop (JVM)** | LiteRT-LM Kotlin for JVM desktop tools: stable [S1] | Same Gemma 4 E2B file [S3] | Published for RTX 4090 GPU (decode 143.4 tok/s) and M4 Max CPU/GPU (41.6 and 160.2 tok/s) [S3] | **Most feasible target.** No mobile store size cap. Packaging with the native libraries is `[UNVERIFIED]` |
| **Web** | LiteRT-LM JavaScript: early preview [S1]. Kotlin/Wasm is not listed | Web-specific E2B file 2,008 MB [S3] | M4 Max WebGPU, E2B: decode 73 tok/s, first token 1.09 s [S3] | **Not for v1.** The app is web-deferred, and a JS interop actual would be needed. Stays rules-only |

### Apple's own on-device model (iOS, macOS)

Apple's Foundation Models framework runs the on-device model behind Apple Intelligence. Secondary coverage says it is free to call, runs offline, and is on iOS 26 and later on Apple Intelligence devices [S8 and secondary sources, all unverified]. The Apple documentation page returned only its title when fetched, and the Apple newsroom page returned 404, so **none of Apple's own availability or cost claims could be verified in this research**. If it matters, the check is on an iOS 26 device, against Apple's documentation. It would cover only iOS 26 devices, while our iOS deployment target is 18.0, so it would be an optional branch, not the iOS path.

### Model size and store delivery

- Gemma 4 E2B's CPU file is 2,583 MB on disk [S3]. Play's individual asset-pack limit is 1.5 GB, and the base module limit is 500 MB, both measured on compressed download size, with 30 GB cumulative for on-demand packs [S13]. The model cannot ship in the APK or as **one** asset pack.
- Gemma 3 1B's file is about 529 MB [S7], which is also above the base module limit.
- That does not by itself force a host of our own. Three delivery paths are open, and none was tested here `[UNVERIFIED]`:
  1. **Android AI Core** on supported phones (see the Android row): the system holds the model, so nothing is ours to deliver.
  2. **Split Play asset packs.** Two or more on-demand packs under 1.5 GB each, reassembled into one file on the device. This stays inside Play's cumulative limit and needs no server, but it doubles the storage while reassembling, and whether LiteRT-LM can read the parts in place was not checked. iOS has no equivalent cap check here (On-Demand Resources was not researched).
  3. **A download from a host we run or pay for.** That brings in a network call and a privacy statement (the download reveals the IP address, as the ConfigCat decision already records in ADR 0026), and a hosting cost that is not priced here.
- Desktop has no store size cap, so the question is only about the phones.
- Play's size-limit figures conflict across sources. The primary page [S13] says 500 MB for the base module, and a search snippet said 200 MB [unresolved]. The conclusion does not change at either number.

### Latency and battery

- **Latency, published:** the figures above (time to first token and decode rate) are Google's published numbers for a flagship phone [S3]. The Gemma 3 1B card also prints S24 Ultra CPU decode at 47 to 55 tok/s and first token at about 3.1 s [S7].
- **Latency, estimate (not measured):** a pick session is about 2,000 prompt tokens and about 200 output tokens (assumed). Using the S26 Ultra GPU rates, that is about 0.3 s to first token plus about 3.8 s to decode, roughly 4 s. The token counts are assumed, so treat the total as an order of magnitude only.
- **Battery:** no published battery figure was found in the pages retrieved. Battery is **not researched and not measured** (#594).
- **Memory:** published peak memory for E2B ranges from about 0.7 GB (GPU path, S26 Ultra) to about 1.7 GB (CPU path) [S3]. Low-end phones are not covered by the published tables.

## Licence

### Gemma 4 is Apache-2.0

The Gemma terms page [S6] said Gemma 4 has a separate Gemma 4 licence. The Hugging Face metadata for `google/gemma-4-E2B-it` says `License: apache-2.0` and links to the licence [S4]. The licence page itself reproduces the standard **Apache License 2.0**, Version 2.0, January 2004, with no field-of-use restriction and no Gemma-specific terms [S5]. Read together, the Gemma 4 weights are Apache-2.0.

Consequences:

- It passes the repo's allow-list in substance. Redistributing it needs a copy of the licence and notices (Apache-2.0 section 4), not a Gemma notice file.
- The allow-list check does not reach it, because the weights are downloaded and not declared as a dependency. Whether the allow-list is meant to cover shipped non-code assets is not written down. That policy question needs a decision before the model is downloaded in a release build.

### Gemma 3, 3n and 2 are not Apache-2.0

The Gemma Terms of Use [S6] require the downstream user to pass the restrictions on to recipients, give recipients a copy of the Agreement, and include a Notice file. They also let Google restrict usage "remotely or otherwise." The Gemma 3 1B card names its licence "gemma" [S7], and the MediaPipe page says that Gemma-3n users "are responsible for complying with any applicable license terms" [S2]. These are outside the allow-list's spirit and carry a remote-restriction clause. **Do not ship Gemma 3.** Gemma 4 is the only candidate.

### LiteRT-LM runtime licence

`[UNVERIFIED]`. The LiteRT-LM repository licence was not fetched in this pass. It must be checked before a Maven dependency is added.

## Remote recommendation service (Supabase Edge Function)

**Can it run the model?** No. Supabase Edge Functions have a 150 s wall-clock limit on the free plan and 400 s on paid plans, a 2 s CPU limit per request, and a 256 MB memory limit [S10]. A 2.6 GB model cannot load. The service would be a model host somewhere else, and that is not priced here `[UNVERIFIED]`. A rules-only scorer inside an Edge Function would be possible, but it would duplicate the on-device rules and add nothing.

**Cost per session.** The invocation price is the only Supabase price the research found. The free plan includes 500,000 invocations, and the Pro plan starts at $25 a month, includes 2 million, then costs $2 per million over that [S9]. At the overage rate, one invocation is $0.000002, so invocation count is not the cost. The cost is the model host, which is unpriced `[UNVERIFIED]`.

**What leaves the device.** At minimum, candidate ids, ratings and genre sets (the design choice in the original sketch). A remote ranker needs the user's history to score, so history leaves the device. `docs/PRIVACY.md` would need a new network-call entry and a changed statement that the app has no server of its own.

**Auth and abuse.** A signed-out user has no account (ADR 0019 ties sign-in to sync). An unauthenticated endpoint that runs a model is an open cost surface. Whether Supabase verifies JWTs by default is not stated on the limits page [S10] `[UNVERIFIED]`, and rate limiting would be ours to build.

**Offline.** No. That breaks ADR 0032's "Free, offline-capable".

**The paid gate (ADR 0018, ADR 0019).** Sync is paid, and its entitlement is a `sync_until` claim. A free remote ranker would be a second server-side surface that ADR 0019 does not plan, and it would need its own auth and rate limit. A paid one contradicts ADR 0032 ("Free"), and CLAUDE.md says paid features never appear on the landing page, so a paid AI pick would be paid and invisible. Neither reading is comfortable.

**Other data.** The service would need pooled data from other users to be a true recommender ("people who liked X"). Co-watch is the one existing exception to the no-social rule (ADR 0022). Pooled recommendations would be a second exception, and nothing in the spike needs it.

**Verdict: reject for v1.**

## Cloud LLM

**Key custody (ADR 0027, ADR 0026).** A key built into the binary is extractable. ADR 0026 already records that a served credential is "no more secret than one in the APK." For a cloud LLM, a built-in key would be a bill we pay for every session, exposed to anyone who extracts it. A user-supplied key follows the ADR 0027 pattern (stored through `SecretStore`, never silently replaced, scrubbed from crash reports), but it needs a user to make an API account and pay for it, and the app would need a new consent flow. A key held in a server-side proxy is the remote-service option above, with the same auth and cost problems.

**Cost per session.** Arithmetic on Anthropic's published prices [S11], assuming 2,000 input and 200 output tokens and no prompt caching (both assumptions):

- Haiku 5.5, prompts up to 100,000 tokens: input $0.10 per million tokens and output $0.50 per million [S11]. Input 2,000 tokens is $0.0002 and output 200 tokens is $0.0001, about **$0.0003 per session, about $0.30 per 1,000 sessions.**
- A cached system prompt is priced at $0.01 per million tokens for cache hits [S11], which lowers the input share further.
- Sonnet 5.5 input is $2 per million [S11], about 20 times Haiku's input rate. The Haiku choice is the one that makes cost negligible.

Cost is not the reason to reject this. Three cents per hundred sessions is affordable. The reasons are key custody and privacy.

**Privacy.** The prompt carries titles, ratings and genres, which are personal. Anthropic's commercial terms say "Anthropic may not train models on Customer Content from Services" [S12]. The terms state no retention period for API inputs and outputs and point to a DPA that was not read [S12]. A cloud call would be a data-egress feature that `docs/PRIVACY.md` must describe, and it would need an opt-in.

**Offline.** No. Failure modes (rate limits, refusals, nonsense output) fall back to rules through the seam, which is why the seam needs the amendment below.

**Verdict: reject for v1.** A bring-your-own-key option could be revisited if users ask for it, and it would get its own ADR at that point.

## The seam: is `PickRanker` enough?

The seam is `fun interface PickRanker` in `feature/nextpick/domain/.../PickRanker.kt`, lines 12 to 14. `PickEngine.pick` (`PickEngine.kt`, lines 60 to 68) gathers candidates, filters them, then calls `ranker.rank(...).take(limit)`.

**Shape: sufficient.** Criteria and candidates in, an ordered list of `RankedPick` out. Sources, filters and exclusions stay in the engine. An on-device or cloud ranker only has to score.

**Enforcement: not sufficient.** The research's four guarantees against the code:

1. **"A bad model output can reorder picks but never show a muted or already-ruled title."** The engine filters before ranking, so a ranker only sees allowed candidates. But it returns whatever it likes and `pick` does not check it. A ranker that returns a foreign id, a duplicate, or an id outside the pool is trusted. The guarantee is true by convention, not by enforcement.
2. **"Any failure falls back to the rules ranker."** Missing. A throwing or slow ranker fails the session, and `PickRanker.rank` is not `suspend`, so a model call cannot be made without blocking.
3. **"The reason shown is localized; if a model writes it, language and length are constrained."** `PickReason` is sealed data with no free text (`PickCandidate.kt`, lines 56 to 72), and its KDoc says the domain does not speak a language. A model-written line has no place to go without breaking that rule. The recommendation is that reasons stay rules-generated.
4. **"Off by default and visibly optional if anything leaves the device."** Not a seam concern. It belongs in the composition root and Settings, the same way ADR 0027 and ADR 0026 gate their features.

**Amendments, filed as #595:**

- `PickRanker.rank` becomes `suspend`. `RuleBasedRanker` changes only its modifier.
- `PickEngine` keeps only picks whose id is in the gathered pool, rebinds each accepted pick to the canonical candidate from that pool (a `RankedPick` carries a whole `PickCandidate`, so an id check alone would still let a ranker swap the summary, source or library status), drops duplicates by id, applies `limit`, and on an exception or a `withTimeout` expiry falls back to `RuleBasedRanker`.
- Reasons stay rules-only, with no free-text variant, until a measured model ranker needs a generated line.

## Spike plan (#404)

1. Build a fixed evaluation set of about 30 histories with expected good and bad picks, judged by hand. **Not done.** This is part of #594.
2. Run the rules ranker as the baseline. **Not done.** Part of #594.
3. Try the on-device option on one Android device: size, latency, battery, quality against baseline. **Not done.** Filed as #594, with the list of what remains.
4. Price a remote and a cloud variant on the same set. **Partly done:** invocation and per-token prices are above. The model-host price for a remote variant is unpriced `[UNVERIFIED]`.
5. Record the decision. **Partly done:** see Recommendation.

## Recommendation

1. **Keep v1 rules-only** (ADR 0032 stands).
2. **Measure Gemma 4 E2B on Android, then on desktop, with #594.** Adopt it only if it clearly beats the rules baseline on the 30-item set and there is a delivery path for about 2.6 GB that our privacy and store policy accept (AI Core, split asset packs or a host, in that order of preference). Otherwise close #404 as "rules are enough".
3. **Reject the remote recommendation service for v1.** It needs pooled data, a sign-in path and a privacy change, and it conflicts with ADR 0032 and ADR 0019.
4. **Reject the cloud LLM for v1.** Key custody and egress outweigh a very small per-session cost.
5. **Amend the seam before any ranker lands (#595).** The shape is right. The enforcement is missing.
6. **No ADR now.** Rejecting the remote and cloud options follows from ADR 0032 and is reversible. The on-device decision waits for measurement. If it is adopted, that decision will be hard to reverse (a 2.6 GB download, a new runtime on four targets, and a licence position) and will get an ADR then.

## Sources

Retrieved 2026-10-27 unless marked.

- **[S1]** Google, LiteRT-LM overview: platform list, API status (Kotlin stable, Swift and JavaScript early preview). Page says "Last updated 2026-10-06 UTC." https://developers.google.com/edge/litert-lm/overview
- **[S2]** Google, MediaPipe LLM Inference API: "now in maintenance-only mode", recommends migrating to LiteRT-LM; lists Gemma 3n, Gemma 3 1B and Gemma 2 2B; no latency or memory figures. Redirected from https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference. https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference
- **[S3]** litert-community, Gemma 4 E2B LiteRT-LM model card: file sizes (2583 MB CPU/GPU, 2722 MB NPU, 2008 MB web), license `apache-2.0`, and device benchmark tables (publisher-reported). https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- **[S4]** Google, `google/gemma-4-E2B-it` model card: metadata `License: apache-2.0`; 2.3B effective parameters (5.1B with embeddings); 128K context; no memory or latency figures. https://huggingface.co/google/gemma-4-E2B-it
- **[S5]** Google, Gemma 4 licence page: reproduces Apache License 2.0, Version 2.0, January 2004; "Last updated 2026-04-01 UTC." https://ai.google.dev/gemma/docs/gemma_4_license
- **[S6]** Google, Gemma Terms of Use: downstream obligations (section 3.1), remote-restriction clause (section 3.2), and a note that Gemma 4 has a separate licence (superseded for Gemma 4 by [S4] and [S5]). Page dated 2026-04-01. https://ai.google.dev/gemma/terms
- **[S7]** litert-community, Gemma3-1B-IT model card: licence "gemma"; file sizes (529 MB int4 `.litertlm`, others); device benchmarks including Galaxy S24 Ultra CPU and GPU. Some size cells were unclear in the scrape. https://huggingface.co/litert-community/Gemma3-1B-IT
- **[S8]** Apple, Foundation Models documentation. **UNVERIFIED:** the page returned only its title. Secondary coverage, not primary, for availability (iOS 26) and cost: https://9to5mac.com/2025/09/29/apple-intelligence-ios-26-models-developers/ (secondary, unverified). https://developer.apple.com/documentation/FoundationModels
- **[S9]** Supabase, pricing: Free plan 500,000 Edge Function invocations and 500 MB RAM; Pro from $25 a month with 2 million included and $2 per million overage. https://supabase.com/pricing
- **[S10]** Supabase, Edge Functions limits: wall clock 150 s free and 400 s paid; CPU time 2 s per request; memory 256 MB. No last-updated date; JWT verification default not stated. https://supabase.com/docs/guides/functions/limits
- **[S11]** Anthropic, API pricing: Haiku 5.5 (prompts up to 100,000 tokens) $0.10 input, $0.50 output, $0.01 cache hit per million tokens; Sonnet 5.5 input $2 per million. https://platform.claude.com/docs/en/about-claude/pricing
- **[S12]** Anthropic, commercial terms: "Anthropic may not train models on Customer Content from Services"; effective "Jun 17, 2025"; no retention period stated, DPA incorporated by reference and not read. https://www.anthropic.com/legal/commercial-terms
- **[S13]** Google Play Console Help, app size limits: base module 500 MB, individual feature modules 500 MB, individual asset packs 1.5 GB, cumulative on-demand 30 GB; "All Google Play size limits are based on the compressed download size." No last-updated date. A search snippet gave 200 MB for the base module; the primary page is used here. https://support.google.com/googleplay/android-developer/answer/9859372

Repo files cited: `feature/nextpick/domain/src/commonMain/kotlin/com/codingpit/muviss/feature/nextpick/domain/PickRanker.kt`, `PickEngine.kt`, `PickCandidate.kt`; `gradle/libs.versions.toml`; `app/iosApp/iosApp.xcodeproj/project.pbxproj`; `app/androidApp/build.gradle.kts`; `docs/PRIVACY.md`; `docs/adr/0018`, `0019`, `0026`, `0027`, `0032`.
