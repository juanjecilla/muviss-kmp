# Feature requests go through a hosted form opened in the browser

Muviss has no way for a user to say what it should do next, and no server of its own to receive a message. `docs/PRIVACY.md` promises that nothing a user enters is sent anywhere. We wanted a Settings row for feature requests, a notification for every response, and nothing that costs money.

## Decision

The **Request a feature** row in Settings > About opens a hosted form in the **system browser** (`LocalUriHandler.openUri`, the same call the OAuth sign-in uses). The app posts nothing itself. The link carries four hidden fields — `version`, `platform`, `os`, `locale` — and nothing else: no install or account id, nothing from the library. `FeatureRequestContext` is a closed type so adding a field is a visible change that has to amend the privacy policy first.

**Tally is the provider**; **Typeform is a switchable fallback**. Remote Config serves `featureRequestProvider` (`tally` | `typeform` | `off`, an unknown value reads as `tally`) and `featureRequestUrl`; the build supplies the compiled defaults (`FEATURE_REQUEST_PROVIDER`, `FEATURE_REQUEST_URL`). The provider matters to the app because Tally reads hidden fields from the query string and Typeform from the URL fragment (`FeatureRequestLink`). A blank URL or `off` hides the row, and the compiled URL is blank, so a build ships **dark** until the owner has created the form — the same posture as the Own TMDB Key (ADR 0027).

The form is defined in `scripts/feedback/tally-form.json` and created and updated by `scripts/feedback/tally-form.sh` over Tally's API, so it is reproducible like the rest of the owner tooling. The API key stays with the owner and never ships in the app. For now each response reaches the owner by Tally's own email notification.

## Considered options

- **Typeform as the primary.** It has Create, Responses and Webhooks APIs, but its free plan allows about 10 responses a month for the whole account and closes the form at the cap. Kept as a fallback only because the seam is cheap.
- **Tally's API to receive the submission from a native form.** Tally's API can create and update forms, list submissions and create webhooks, but it **cannot create a submission**, so a native Compose form would need a backend of our own — a server holding user text, which this product does not have.
- **An in-app WebView.** A different implementation on each of six targets, awkward on desktop and web, for no gain over the browser.
- **Google Forms.** Free and unlimited, but notifications need Apps Script and there is no form-management API worth the name for this.
- **GitHub issues as the form.** Needs a GitHub account, which most users of a movie tracker lack.

## Consequences

- `docs/PRIVACY.md` gains a Feature requests section and its "no other network calls" and "nothing you enter is ever sent to a server" sentences are narrowed: the app makes no such call; the user's browser opens a Tally page, and what they type there goes to Tally, not to Muviss.
- The row exists on every target, web included, since opening a URL needs nothing platform specific.
- Moving or switching the form needs no release (ConfigCat), but the first value has to be set by the owner (`docs/RELEASING.md` section 13).
- Each response becomes a public GitHub issue through a polled Ops Bridge connector (#448): the text is copied, the email never is, and the form and the privacy policy say so. A webhook was rejected because Tally cannot call GitHub's dispatch API and a receiver means hosting (ADR 0028).
- A bug report form is a follow-up (#449).
