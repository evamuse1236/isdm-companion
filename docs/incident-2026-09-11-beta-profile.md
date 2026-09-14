# Beta profile confirmation during the collection pause

Fixed and deployed on 11 September 2026. Live verification completed at
09:10 IST / 03:40 UTC.

## Failure and cause

The user reported “Could not update the beta profile. Try again.” on a connected
phone. The deployed `beta-api` version 7 returned HTTP 202 and
`{"saved":false,"collection_paused":true}` for every authenticated profile POST
while collection was paused. Android's `BetaApiClient.updateProfile` accepts
200/201 and expects profile fields in the response. It therefore threw before
`BetaManager.updateProfile` could confirm and save the profile locally.

The real-handler regression command was:

```bash
node --test --test-name-pattern='paused profile confirmation' tests/beta-access-handlers.test.mjs
```

Before the fix, the valid-profile assertion failed with `202 !== 200`; the
malformed-input assertion also failed with `202 !== 400`.

## Change

The paused profile route now uses the existing profile validator and returns
the normalized profile with HTTP 200, `saved: false`, and
`collection_paused: true`. The supplied store returns the profile without any
database write. Existing Android clients can persist their confirmed profile
on the phone. Installation authentication and suspension checks still execute
first. Other paused routes retain their existing responses.

Deployed `beta-api` version 8, retaining its existing installation-token
authentication configuration. All bundled dependencies matched version 7;
only the intended handler change was deployed. No APK installation is needed
for this server response change.

## Verification

- All 27 root tests passed, including real-handler checks for profile values,
  zero writes, malformed input, suspension, and the other paused routes.
- The incident-response Android verification script passed all 194 JVM tests,
  lintDebug, and assembleDebug. No connected instrumentation or install ran.
- A temporary synthetic installation exercised the production HTTP endpoint:
  valid profile returned 200 with normalized fields; malformed profile returned
  400; missing installation authentication returned 401.
- A database read confirmed the synthetic installation had no stored support
  name, profile confirmation, section, PLC, or detected cohorts after the request.
  The temporary installation was then deleted and its local token file removed.
- `git diff --check` passed.

## Device verification gap

ADB detected the connected phone as `unauthorized`. The read-only diagnostics
collector could not inspect the package version, logs, or app state. No phone
data was cleared and the app was not restarted or replaced. The deployed
contract failure is reproduced and fixed; confirmation on the user's specific
phone remains pending USB-debugging authorization or a successful user retry.
