# Desktop OAuth comes back over a loopback server, not the `muviss://` scheme

Date: 2026-09-02. Amends ADR 0014, which said desktop "reports sign-in as
unsupported" and named a loopback HTTP server as the eventual answer.

## Context

Sign-in (ADR 0014) is OAuth + PKCE: the app opens the provider's page in a
browser, and GoTrue redirects back to `OAUTH_REDIRECT_URI` with a `code`.
Android and iOS both own the `muviss://` scheme — an intent filter and
`CFBundleURLSchemes` respectively — so the OS routes the redirect back to the
app whatever state it is in, including a cold start.

Desktop had no route at all. `nativeDistributions` registers no scheme, and
`Main.kt` passed no `oauthCode`, so `CompleteOAuthOnRedirect` could never fire.
`ProfileScreen` is `commonMain` and ungated, so the button was on screen, the
browser opened, and the user came back to a screen identical to one where they
had never tried. `SignInFeedback` reported nothing either, because nothing
failed — nothing ran.

## Decision

Desktop binds a short-lived HTTP server on loopback and gives GoTrue that URL as
`redirect_to`. This is RFC 8252's answer for native apps.

`OAUTH_REDIRECT_URI` stays what it is for the platforms that own a scheme. The
value varies through `OAuthRedirectTarget`, a Koin binding: `syncModule` binds
`DeepLinkRedirectTarget` (returns the constant, releases nothing) and
`:app:desktopApp` rebinds it to `LoopbackRedirectServer`. Last binding wins, the
same mechanism `BillingSyncBridge` uses for `EntitlementGate`, and with the same
ordering requirement.

`reserve()` suspends and can fail, because on desktop it also binds the socket.
Three ports (53682-53684) are pre-allowlisted and tried in order. The listener
lives only for one attempt: it closes on the code, on an error redirect, on a
five-minute timeout, and on a second sign-in click superseding the first.

The captured code goes into `MuvissApp(oauthCode = ...)` — the same parameter
`MainActivity` feeds — so `CompleteOAuthOnRedirect` remains the only caller of
`completeOAuth` anywhere.

## Why not the `muviss://` scheme

It is the obvious answer and it does not work here.

- jpackage writes no Windows registry protocol handler and no Linux `.desktop`
  `MimeType` entry. Both would be post-install steps it cannot generate.
- A scheme belongs to an **installed bundle**. `./gradlew run` — how this app is
  actually developed — owns nothing, so sign-in would be untestable in the loop
  where it is written.
- It needs single-instance handling: a second launch has to forward the URL to
  the running process rather than start a rival one.
- Only macOS has a portable Java hook for it (`Desktop.setOpenURIHandler`).

Loopback needs none of that and behaves identically on all three OSes.

## Consequences

**PKCE still carries the security, and still has to.** A loopback port can be
bound by any local process exactly as a custom scheme can be claimed by any
installed app, so possession of the code must remain insufficient. Nothing about
the verifier changes.

**The allow-list grew a fourth place to keep in sync**, and it is the one that
fails latest. `supabase/config.toml` must name every port, applied with
`supabase config push`. Worth knowing precisely when a drift bites: GoTrue's
`/authorize` **accepts any `redirect_to` and 302s to the provider regardless** —
verified against the live project, an unlisted port redirected just as happily as
a listed one. Enforcement happens when GoTrue redirects *back*, after the user
has already authorized, which is why a missing entry presents as ADR 0014's
"cannot connect to the server" on `site_url` rather than as an error up front.

**`jdk.httpserver` joins the jlink module list.** Same hazard as `java.sql`:
nothing in application code names the module, and dropping it is a
`NoClassDefFoundError` in the packaged app only.

**Desktop now starts Koin before Compose**, as Android and iOS already did, since
the server must be in the graph for `CoreSyncRepository` to resolve it.

**A port can still be unavailable.** Three is a guess, not a proof: with all
three taken `reserve()` throws and the profile screen says so. Failing visibly is
the point — the bug being fixed was a silent one.
