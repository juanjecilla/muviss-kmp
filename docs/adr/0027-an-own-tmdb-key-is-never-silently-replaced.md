# An Own TMDB Key is never silently replaced

A user may supply their own TMDB credential (EPIC 46). Three credentials can then exist at once: the user's, a fallback token served by Remote Config (ADR 0026), and the one built into the binary.

## Decision

The credential used is the first one **present**, in that order: user → remote → built-in. A level is skipped only when it is absent, never because it failed. When TMDB rejects the user's credential, the app says so ("your TMDB key was rejected") and offers to change it; it does **not** retry with Muviss's credential.

The Remote Config switch that offers the feature controls only whether the app *offers* to take a key. Turning it off never stops a stored key being used.

## Why not fall back silently

It would look kinder — the app keeps working — but it would hide exactly what the user chose: their requests quietly made under someone else's account, with nothing telling them their own key has stopped working. Falling back is also untestable from the user's side, since every screen would look the same either way.

## Why the switch does not strip existing keys

The switch exists to stop *offering* the feature (support load, a TMDB policy change). Removing a working key from people already using it would break their app to solve a problem they did not cause.

## Amendment, 2026-10-12 (grilled before building EPIC 46)

- **A rejected key is its own error**, `MetadataError.OwnKeyRejected`, not `Unauthorized` with a flag: `Unauthorized` is a configuration fault only a new build can fix, this one is the user's to fix, and a sealed type makes the compiler find every renderer. The copy names the place to fix it (Settings, TMDB data source). A button that opens Settings from each error state is not built: every screen holds its error as plain text, so it is a design-system change of its own (filed as a follow-up).
- **The chain is `TmdbCredentialSource`**, asked once per request, so saving or clearing a key applies to the next request. `TmdbProvider` maps a 401 to `OwnKeyRejected` only when the credential used was the user's.
- **Storage is KSafe** (`eu.anifantakis:ksafe`, Apache-2.0, published for all six targets), behind `SecretStore` in `:core:common`. One named store (`secrets`) so `clearAll` wipes exactly the secrets, key material included: an iOS Keychain item survives an uninstall, and "Delete all data" must not leave it. A value the store cannot give back (a Keystore key lost to a restore) reads as "no key", never as an exception that could reach a screen.
- **Validation** is one `GET /3/authentication` through the same policy and error mapping as every other TMDB call. Only a 401 says "rejected"; offline, throttled or failing says "could not check", and either way nothing is saved.
- The key shapes (a JWT, 32 hex characters) are also scrubbed from crash reports by shape, because a typed key can appear in text that never names it.

