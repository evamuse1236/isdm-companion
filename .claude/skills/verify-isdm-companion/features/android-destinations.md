# Schedule, Readings, Profile (Android)

The three destinations of the signed-in app (see `README.md` and `CONTEXT.md`): Schedule with Scheduled Sessions and Mark actions, Readings with local Done/Undo, Profile with Section/PLC, LMS attendance progress and automatic-attendance setup.

## Sub-features

- Schedule, Readings, Profile tabs; nightly enrollment ("Join nightly"); automatic-attendance setup; issue reports; Save debug logs.

## How to get to it (user POV)

Sign in with a real ISDM LMS account.

## Driving it

Unreachable for agents: it needs a real LMS login, and Mark actions are gated by the Attendance Location Gate and post to the LMS. Do not sign in, do not mark attendance, do not join nightly (that writes to the production roster). Report these as UNVERIFIED. JVM tests under `android/app/src/test` cover parsers, rules and view-model behaviour with fixtures; `npm test -- android` runs them.

## Gotchas

- A prevented Mark action is the Attendance Location Gate working, not an app failure.
