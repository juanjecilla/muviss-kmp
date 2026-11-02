# First-run onboarding, and notifications are opt-in

Muviss asked for notification permission cold: Android once a show was saved (a dialog, EPIC 30), iOS on every launch. A person got the question before they had a reason to say yes, and on Android 13+ a refusal is sticky. EPIC 30's audit recorded it: "No onboarding. Notification permission asked cold."

## Decision

- **A skippable first-run introduction** (*Onboarding*, CONTEXT.md) — welcome, library and progress, watch next, discover, import, new-episode alerts, an Own TMDB Key page only while `byokEnabled`, done. Skip is on every page, including the first; each page writes as it is changed, so Skip only ends the flow and marks it completed.
- **Notifications are off by default, for fresh installs only.** `ensureRow` seeds `notificationsEnabled = 0`; an upgraded install keeps whatever it stored. Silently turning someone's alerts off, or on, is worse than the inconsistency.
- **First run only.** `onboardingCompleted` defaults to 0 and `17.sqm` sets it to 1 on every row that exists — and creates the row first when there is none, because an `UPDATE` alone would leave `ensureRow` to make it later as a fresh install's. A migration only runs on an install that already has the app, which is the whole reason this is safe.
- **The OS is asked at the moment the user turns alerts on**, never before: one `NotificationPermissionRequester` seam, called by `SettingsApi.setNotificationsEnabled`, the only door Settings, Onboarding and the Notification Nudge use. A refusal stores nothing, so a switch never reads on for an alert that cannot arrive, and each surface says why it snapped back. Android's cold dialog and iOS's every-launch request are deleted.
- **A Notification Nudge** replaces the old dialog's job: notifications off and the user saves their first TV show, so Detail offers once, non-blockingly, to turn them on. The mark is spent by the ✕ and by "Turn on" alike, whatever the OS answered.
- **A launch that arrives through a widget or a notification starts where it was tapped** and leaves the introduction pending for the next plain launch.
- "Show introduction again" in Settings replays the whole flow over the current values.

## Consequences

- **The nudge cannot tell "never asked" from "refused" on Android 13+**, where both read as not granted. So it is offered once and a refusal is answered with a line pointing at system settings, rather than being suppressed up front. (Below 13 the system switch is exact.)
- **"Delete all data" shows the introduction again**, since it deletes the settings row and `ensureRow` makes a fresh install's. That is what "the app afterwards is a fresh install's" already promised.
- iOS has no `SystemNotificationSettings` yet, so a refusal there is explained but cannot be undone from inside the app.
- The migration number is whatever is free when this merges; it was `17.sqm` after EPIC 45's `16.sqm`.
