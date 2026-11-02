# Next Pick: could a model do the ranking?

Status: **unresearched sketch**. This lists what each option would have to prove. Nothing here is measured; numbers and API names must be checked in the spike (#404) before anyone relies on them. ADR 0032 is why v1 ships without any of this.

## What a model would add

Rules map answers to TMDB filters and score by genre overlap, ratings and favorites. A model could do three things rules cannot: read a free-text request ("something like Severance but warmer"), weigh a title's overview against the user's history, and write a better "because..." line. It cannot add data we do not have: it would still need candidates from our backlog and TMDB.

## The three options

| | On-device small model (Gemma family) | Remote recommendation service | Cloud LLM |
|---|---|---|---|
| Offline | Yes, once downloaded | No | No |
| What leaves the device | Nothing | Ids, ratings, genres (design choice) | Whatever the prompt carries |
| Cost to us | Engineering; binary or download size | A service to run, per-request cost | Per-token cost per session, paid by us or the user |
| Free tier fit | Good | Needs a budget | Needs a budget or a user-supplied key |
| Six-target parity | Hard: each target has a different runtime | Easy: HTTP | Easy: HTTP |
| Risk | Size, memory, battery, slow low-end phones | A new backend we operate | Key custody, prompt privacy, vendor change |

### On-device
Questions to answer: which runtime on each of Android, iOS, Desktop (JVM) and Web, and is any usable from `commonMain` or only through `expect`/`actual`; model size against an APK/IPA and a Play download limit versus a first-use download; latency and battery for ~20 candidates; memory on a low-end device; model licence terms for redistribution; whether a model this small beats the rules at all. Web likely stays rules-only (launch is deferred anyway).

### Remote service
The shape that fits the repo is a Supabase Edge Function (sync already runs on Supabase). Questions: what is sent (only what the user's current session needs), whether it needs sign-in (a signed-out user has no account), whether it counts as the paid tier (it costs money to run), and what the "no social, no tracking" promise allows. Co-watch is the only existing exception to no-social (ADR 0022); this would be a second thing that sends history off the device.

### Cloud LLM
Questions: key custody. ADR 0027 is the precedent: a user-supplied key that is never silently replaced, stored via `SecretStore`, scrubbed by `CrashScrubber`. A built-in key shipped in the app is extractable. Cost per session and who pays; prompt content (titles and ratings are personal); failure modes (rate limit, refusal, nonsense) and the fallback to rules; whether a "bring your own key" option is acceptable for a consumer app.

## What the seam must guarantee whatever we pick

- The AI only **ranks and explains**; candidates, Muted Genres, exclusions and the user's actions stay in the rules engine, so a bad model output can reorder picks but never show a muted or already-ruled title.
- Any failure falls back to the rules ranker with no user-visible error.
- The reason shown is localized; if a model writes it, language and length are constrained and checked.
- Off by default and visibly optional if anything leaves the device.

## Spike plan (#404)

1. Build a fixed evaluation set: ~30 histories with expected good and bad picks, judged by hand.
2. Run the rules ranker as the baseline.
3. Try the on-device option on one Android device: size, latency, battery, quality against baseline.
4. Price a remote and a cloud variant on the same set.
5. Record the decision as an ADR (or close the issue as "rules are enough").
