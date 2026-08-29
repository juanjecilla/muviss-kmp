# OAuth sign-in instead of email one-time codes

ADR 0009 shipped an email one-time-code flow: `POST /auth/v1/otp` sends a
code, the profile screen asks the user to type it, `POST /auth/v1/verify`
redeems it. It was never run against a live project. The first time it was,
it did not work, and could not be made to work on the plan this project is on.

## Why the code flow was abandoned

Signing in produced a real email — so the request half was correct — but the
email contained a **link**, not a code. GoTrue's stock `confirmation` and
`magic_link` templates both render `{{ .ConfirmationURL }}`; neither renders
`{{ .Token }}`. There was nothing in the message to type into the app, and
following the link instead landed on `site_url` with
`error_code=otp_expired`, which reads like a broken token and is in fact a
consumed link the app never wanted.

The OTP itself was fine throughout. Only its presentation was wrong.

The fix is one line per template, and Supabase refuses it:

> Email template modification is not available for free tier projects using
> the default email provider. Please upgrade your plan or configure a custom
> SMTP provider.

That leaves three ways to keep the code flow: pay for a plan, run an SMTP
provider, or change the app. Per explicit product direction — no paid plan and
no third-party email service — the app changes.

Worth recording even so: the built-in sender is rate-limited to a couple of
messages an hour and Supabase documents it as unsuitable for production, so a
code flow would have needed SMTP eventually regardless. The free-tier
restriction only forced the decision earlier.

## OAuth, and what was rejected

Considered, inside the free tier and without external services:

- **Email + password**, with `enable_confirmations = false` so no email is
  ever sent. Smallest change and genuinely works. Rejected because it
  reintroduces passwords, which ADR 0002 and ADR 0009 both deliberately
  avoided, and because nothing would prove ownership of the address.
- **Anonymous sign-in**, which already exists on `SyncBackend` and needed only
  a config flag. Rejected as *sign-in*: an anonymous user is per-device, so
  two devices get two identities and nothing syncs between them — which is the
  entire point of the feature. It remains on the interface for a future entry
  point, exactly as ADR 0009 left it.
- **Magic link plus a deep link**, which works with the stock templates
  untouched. Rejected for the rate limit above, and because it is the same
  Android deep-link work as OAuth for a worse result.

OAuth sends no email at all, needs no SMTP, costs nothing on the free tier,
and gives a real cross-device identity.

**The client is provider-agnostic.** GoTrue takes the provider as a query
parameter, and everything after the redirect is identical, so `OAuthProvider`
is an enum and adding Google is a dashboard change plus one entry — not a new
code path. GitHub is enabled first purely because registering an OAuth app
there is a short form, where Google wants a Cloud project, a consent screen
and per-signing-key SHA-1 fingerprints.

## PKCE, not the implicit flow

The redirect target is `muviss://auth-callback`, a custom scheme, because an
App Link would need a domain Muviss does not own. Any app on the device can
register that same scheme, so the redirect must not be enough on its own to
obtain a session.

So the flow is PKCE (RFC 7636, `S256`): the app generates a random verifier,
sends only its SHA-256 in the authorize URL, and produces the verifier when
redeeming the code. An intercepted redirect is then useless without it. The
implicit flow — tokens delivered straight into the redirect fragment — was
rejected for exactly this: it puts access and refresh tokens somewhere any app
holding the scheme can read, and into browser history.

Nothing in the build provides SHA-256: Kotlin's stdlib has no hashing.
Rather than add a multiplatform crypto dependency to a Kotlin 2.4 / AGP 9
toolchain — the version-fighting ADR 0009 already declined once over
`supabase-kt` — `sha256` and `secureRandomBytes` are `expect`/`actual`, real
on Android and JVM via `java.security`, unimplemented on the targets that
cannot do a browser round-trip anyway. `kotlin.random.Random` is deliberately
not used for the verifier: it is a general-purpose PRNG, and a guessable
verifier defeats the whole mechanism.

## The redirect string lives in three places

`muviss://auth-callback` must be byte-identical in:

1. `OAUTH_REDIRECT_URI` (`:core:sync`), sent as `redirect_to`;
2. the Android manifest's intent filter, which cannot read a Kotlin constant;
3. `supabase/config.toml`'s `additional_redirect_urls`, pushed with
   `supabase config push`.

The third is a security control rather than bookkeeping: GoTrue refuses to
redirect anywhere not on that allow-list, which is what stops another party
persuading Supabase to deliver a code elsewhere. A drift between the three
fails at the browser, not at compile time, so a test pins the constant.

## Consequences

- `SyncBackend` loses `requestEmailOtp`/`verifyEmailOtp` and gains
  `beginOAuth`/`completeOAuth`. Two calls rather than one because the user
  leaves the app in between.
- **Completion does not return to the caller.** The redirect arrives as an
  intent, possibly on a cold start and long after `ProfileViewModel` is gone,
  so `MuvissApp` redeems it at app scope and the signed-in state reaches the
  UI through `SyncBackend.session` — which is already a Flow. This is the same
  reasoning that put `AutoSyncOnForeground` there.
- The PKCE verifier is held **in memory only**. Persisting it would mean a
  migration for a value that lives as long as a browser visit; the cost is
  that a process death mid-sign-in loses the attempt and the user taps the
  button again. The verifier is single-use and cleared on both success and
  failure, so a replayed redirect cannot redeem a second code.
- `SyncAccountState.SignedIn.email` stays nullable — a GitHub account with a
  private email signs in without one, where the code flow always had an
  address by construction.
- The two-step email/code dialogs are gone, replaced by one button per
  provider. `SyncUiState` lost `isEnteringEmail`/`isEnteringCode`/
  `pendingEmail` and gained `pendingAuthUrl`, which the screen hands to
  `LocalUriHandler` and the ViewModel then clears so returning to the screen
  does not reopen the browser.
- iOS, desktop and web report sign-in as unsupported. Each needs a different
  browser round-trip — `ASWebAuthenticationSession`, a loopback HTTP server,
  an in-page redirect — and ADR 0003 makes Android the first-verify target.
  Everything still compiles on all six.
- `supabase/config.toml` no longer carries email templates, which is what
  unblocked `supabase config push` — the auth update is atomic, so while the
  templates were in the file nothing else in `[auth]` could be applied either.
