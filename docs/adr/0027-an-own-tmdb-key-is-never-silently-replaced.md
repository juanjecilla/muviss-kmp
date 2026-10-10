# An Own TMDB Key is never silently replaced

A user may supply their own TMDB credential (EPIC 46). Three credentials can then exist at once: the user's, a fallback token served by Remote Config (ADR 0026), and the one built into the binary.

## Decision

The credential used is the first one **present**, in that order: user → remote → built-in. A level is skipped only when it is absent, never because it failed. When TMDB rejects the user's credential, the app says so ("your TMDB key was rejected") and offers to change it; it does **not** retry with Muviss's credential.

The Remote Config switch that offers the feature controls only whether the app *offers* to take a key. Turning it off never stops a stored key being used.

## Why not fall back silently

It would look kinder — the app keeps working — but it would hide exactly what the user chose: their requests quietly made under someone else's account, with nothing telling them their own key has stopped working. Falling back is also untestable from the user's side, since every screen would look the same either way.

## Why the switch does not strip existing keys

The switch exists to stop *offering* the feature (support load, a TMDB policy change). Removing a working key from people already using it would break their app to solve a problem they did not cause.
