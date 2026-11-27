# fOCUS 0.1.0 — release notes (DRAFT)

**Not released.** Nothing has been built, tested or run in CI yet. Do not publish these notes until the Phase 08 checks in `progress.txt` are done and the results are filled in below.

## What it does
- Focus sessions with presets (15 m to 24 h) or a custom duration; the exact end time is shown before starting.
- Modes only as far as the device supports: Selected Apps and Full Focus on a Device Owner device (apps are suspended, Lock Task optional); Call only silences notifications on a regular device with Do Not Disturb access; Scheduled Focus runs the supported modes on a schedule. Everything else is shown as "unsupported".
- Session state survives process death, restart, clock and time-zone changes.
- Always-visible emergency entry; early end only through the trusted-person recovery (PIN, wait and lockout rules, local audit log).
- Statistics from the local history only (time restricted, completed, ended early, last 7 days and 8 weeks).
- Local only: no INTERNET permission, no backup, no analytics.

## Known limits
- A normal app cannot stop force-stop, uninstall, reboot or safe mode. fOCUS is not an unbreakable lock.
- "Most-restricted apps" is not built (it would need per-app data).
- WorkManager adds a few normal install-time permissions; the exact list is unconfirmed until an APK is inspected.

## Artifacts (after a successful CI run)
fOCUS-{debug|release}-{arm64-v8a|armeabi-v7a|x86|x86_64|universal}.apk — release only with the four signing secrets.

## Test results
_Pending: unit tests (29 test files written), CI run, APK verification, device run._

## Changed in Phase 08
- `AndroidDeviceControl.kt`: removed `!!`; `strings.xml`: removed unused `result_planned`; README, manifest comment, `progress.txt`, this file.
