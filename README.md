# fOCUS

Android focus and device-restriction app. Package `com.focus.app` · Kotlin · Jetpack Compose (Material 3) · MVVM with clean layers.

**Status: Phase 08 (finalization) — static review done, nothing built or run yet.** The first build, the unit tests and the first GitHub Actions run still have to happen (see "Phase 8: what was checked and what is still open" below). The app has its screens, a real list of launchable apps, a complete session engine, a restriction engine that detects what the device allows and enforces only that (on a Device Owner device it suspends apps and optionally uses Lock Task; on a regular device it can only silence notifications for Call only, and every other mode is shown as unsupported), and an always-visible emergency entry with a trusted-person recovery path (the only way to end a session early). Phase 7 adds a statistics screen built from the local history, a database index, battery and memory fixes, and a review pass for crashes. None of this has been built or tested on a device yet (first build: Phase 8), so treat all of it as unverified.

## What fOCUS can and cannot do

fOCUS never promises an unbreakable lock.

- **Regular device:** a normal app cannot stop force-stop, uninstall, reboot or safe mode, and call handling differs by Android version and manufacturer. `UsageStatsManager` alone cannot block apps. Whatever a regular device cannot enforce is shown as "unsupported", with the safest alternative.
- **Device Owner / managed device:** only when the device is properly provisioned (initial setup or factory reset). Uses `DevicePolicyManager` and Lock Task only where available. No privilege escalation.
- Emergency calling/info and system recovery are never blocked. fOCUS itself and essential system apps are never restricted.

These two modes are separated behind capability interfaces (`DeviceControl`, `ModeSupport`; Phase 5).

## Requirements

- JDK 17 or newer (CI uses 21)
- Android Studio with support for AGP 9.2 (or the command line)
- Android SDK platform 37.1 (`android-37.1`) and build-tools 36.0.0

## Build

```
gradle :app:assembleDebug
```

`gradle/wrapper/gradle-wrapper.properties` pins Gradle 9.4.1, but `gradlew`, `gradlew.bat` and `gradle-wrapper.jar` are **not in this repository yet** (they are binaries and could not be generated offline). Android Studio builds from the properties file as is. To create them once from a local Gradle install:

```
gradle wrapper --gradle-version 9.4.1
```

CI does not need them: it installs Gradle 9.4.1 itself.

## Versions

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.2.1 (built-in Kotlin, new DSL) |
| Gradle | 9.4.1 (minimum required by AGP 9.2) |
| Kotlin / Compose compiler plugin | 2.3.10 |
| Compose BOM | 2026.11.01 |
| Activity Compose | 1.13.0 |
| Navigation Compose | 2.10.2 |
| Lifecycle (ViewModel, runtime-compose) | 2.11.1 |
| DataStore (Preferences) | 1.3.0 |
| Room (runtime, ktx, compiler via KSP) | 2.8.5 |
| KSP | 2.3.9 (2.3.1 or newer is needed for AGP 9 built-in Kotlin) |
| WorkManager | 2.12.0 |
| compileSdk / targetSdk / minSdk | 37.1 / 36 / 26 |

These were taken from the Android and Jetpack release pages and have not been confirmed by a build yet (first build: Phase 08). Lifecycle 2.11.1 and DataStore 1.3.0 (added in Phase 2) were checked against the AndroidX release page as stable on 2026-11-22. Room 2.8.5, KSP 2.3.9 and WorkManager 2.12.0 (added in Phase 4) were taken from the AndroidX release pages and the KSP release listing on 2026-11-22 and are equally unconfirmed. Room 3 (`androidx.room3`) is a different package and is not used. Coroutines/Flow arrive transitively through Lifecycle and DataStore.

## Project structure

```
app/src/main/java/com/focus/app/
  FocusApplication.kt   composition root (only place that picks data/device/session implementations)
  ui/        Compose screens, ViewModels, navigation, theme, shared components
  domain/    pure Kotlin models and interfaces (no Android imports)
  data/      Room / DataStore implementations (local only)
  device/    Android platform adapters (app discovery, icons), capability detection
  session/   SessionEngine: state machine driver, timer, schedules, history
  recovery/  emergency access, trusted-person recovery, audit log
```

Dependency rule: `domain` depends on nothing. `data`, `device`, `session` and `recovery` depend on `domain`. `ui` talks to `domain`, `session` and `recovery`, and never imports `data` or `device` implementations directly. Each package has a short `README.md` with its rule.

## Permissions

fOCUS itself declares **no permissions** (Phases 01 to 08; Phase 6 uses only the system dialer through an intent, and `FLAG_SECURE`, which needs none). There is no `INTERNET` permission: all processing and storage is local. Every permission added later is explained in the app at the moment it is requested.

Phase 4 adds WorkManager, whose own manifest is merged into the app and may add a few *normal, install-time* permissions (for example running after a restart and keeping the device awake briefly). These are never prompted at runtime. The exact merged list has still not been checked, because that needs a built APK: run `aapt2 dump permissions` on `fOCUS-debug-universal.apk` once CI has produced it. Other Android apps report that WorkManager adds `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE` and `FOREGROUND_SERVICE`; treat that as what to expect, not as a result. The in-app copy (guide, intro) already says that such automatic permissions may be listed.

Phase 3 adds a `<queries>` block to the manifest. It is not a permission: it declares which apps fOCUS may see on Android 11+ (apps with a launcher entry, plus the intents that identify the home, phone, emergency and settings apps). `QUERY_ALL_PACKAGES` is deliberately not used.

## UI (Phase 2)

Flow: first launch shows a three-step introduction (purpose, limitations, privacy). After that: dashboard -> mode -> (app selection | schedule | duration) -> review. The review screen lists what is missing, shows the exact end time, and keeps **Start disabled** because this build has no session or restriction engine (`BuildCapabilities.SESSION_START_AVAILABLE = false`; Phase 4/5 replace it with real state and capability detection). The active-session screen renders a running session from `SessionStateSource`; in this build that source is always Idle, so the screen cannot be reached.

State: the onboarding flag is stored with DataStore (local). The in-progress session draft lives in an activity-scoped ViewModel and is intentionally not persisted; only the chosen apps are persisted (see below).

Design tokens: palette in `ui/theme/Color.kt` (spec colors plus two derived tokens: hairline and, for the light theme, a slightly darker secondary text color because the specified `#747B70` is below 4.5:1 for small text), type scale in `Type.kt`, spacing and shapes in `Spacing.kt`. Shared components are in `ui/components`. Text only: no decorative icons, gradients or shadows.

## App discovery (Phase 3)

The app-selection screen lists apps that have a launcher entry in the current user profile, with label and icon, sorted by label. Search matches the label. Tapping a row selects or deselects it; the selection is saved locally (DataStore, a set of package names) and remembered for the next session.

How it works:

- **Package visibility:** `PackageManager.queryIntentActivities` for the launcher intent, allowed by the manifest `<queries>` block. No `QUERY_ALL_PACKAGES`, no permission prompt. Apps without a launcher entry (services, libraries) are not listed because they cannot be opened by the person anyway. Apps in a work or private profile are not listed.
- **Install / remove / update:** while the screen is visible, a package-change receiver triggers a reload (debounced by 500 ms so an update's remove+add burst is one refresh). The receiver is registered only while the screen is shown.
- **Selection stays consistent:** after each successful reload, stored packages that are no longer installed, or that became protected, are removed from the selection. If the list cannot be read, the stored selection is left untouched and the screen shows an error with a retry.
- **Protected apps:** fOCUS itself, the home screen app(s), the default dialer and system dialers, emergency information/assistance, system settings, and a few core AOSP components are shown in a separate "Can't be restricted" section with the reason and have no checkbox. The primary signal is role detection on the device (intent resolution and `TelecomManager`); a short AOSP package list is only a fallback because manufacturers rename packages. The view model also refuses to add a protected package to the selection.
- **Icons:** loaded off the main thread when a row first appears, rendered (adaptive icons included) to pixels, kept in a small LRU cache that is cleared on each reload.

Known limits: protection by role detection is best effort and varies by Android version and manufacturer; apps in other profiles are not covered; the selection is validated again when a session starts (Phase 4/5), because an app may be installed or change role between choosing and starting.

Tests written in Phase 3 (run in Phase 8): `AppListLogicTest`, `ProtectionPolicyTest`, `DataStoreAppSelectionRepositoryTest` (JVM, temporary DataStore file). Behavior on a real device or emulator (package visibility, receiver, role detection, icons) is verified in Phase 8.

## Session engine (Phase 4)

`SessionEngine` (package `session`) implements the `SessionController` interface from `domain`. The UI only talks to that interface.

**States** (`SessionState`): Idle, Configuring, Starting, Active, RecoveryPending, Completed, Interrupted, Failed. A ninth, `Restoring`, is the in-memory placeholder shown until stored state has been read, so a running session is never briefly shown as Idle. `SessionMachine.reduce` is the single table of legal transitions; anything else returns null and is refused. Configuring and Restoring are never stored.

**Honesty rule:** a session becomes Active only after the `RestrictionEnforcer` reported *applied* **and** the Active state was stored. If applying fails, or the state cannot be stored, restrictions are released and the result is Failed (recorded in history with the reason). Phase 4 shipped `NoRestrictionEnforcer` (every mode unavailable); Phase 5 replaced it with `RestrictionCoordinator`, and `NoRestrictionEnforcer` remains only as the truthful stand-in for tests.

**Timer, clock changes and restarts:** the end of a session is tracked on two clocks (`SessionTiming`):
- Within one boot, remaining time comes from `elapsedRealtime` (monotonic). Changing the wall clock or the time zone cannot shorten or extend a session; the displayed end time is corrected on the next reconcile.
- After a restart the wall clock is the only option: remaining = stored end time minus now, never later than the full duration, and never reading the clock as earlier than the last time fOCUS saw it (devices can boot with an unsynced clock). The session is then re-anchored to the new boot. The boot counter is `Settings.Global.BOOT_COUNT` (no permission); without it the monotonic clock going backwards is used.
- Known limits: time the device spends switched off is not counted if the clock is unsynced right after boot (the session ends later, by at most that downtime); and a wall-clock change made *while the device is off* cannot be detected.

**Recovery after process death:** the state is stored atomically (one DataStore value) before it is published. On start-up `reconcile()` restores it, ends it if the time is up, and otherwise re-applies the restrictions (the enforcer's `apply` must be idempotent). If re-applying fails the session is Interrupted with the reason. A stored `Starting` means the process died mid-start: it is released and recorded as Failed. If the stored state cannot be read at all, the screen says so (Failed, internal error) instead of showing Idle.

**Wake-ups:** `reconcile()` is called on process start, every time the app comes to the foreground, by one unique WorkManager request (survives process death and restarts; delivered "at or after", not exactly), and by an in-process timer while the process lives. The decision to end a session always comes from the stored end time, never from when a worker happened to run. A session end can therefore be noticed late if the app is not running; restrictions are only removed by the engine once it has run. Phase 5 makes that robust with a write-ahead journal and a start-up cleanup (see Restriction engine).

**One session at a time, no overlaps:** a second start is rejected while one is running. A manual session cannot overlap a scheduled window, a schedule cannot overlap another schedule or a running session. Overlap between schedules is checked on a circular week of minutes (midnight and week-end crossings, time-zone independent).

**Schedules:** stored in Room. A due window starts a session for the rest of that window (skipped if less than 5 minutes remain). Each window is marked handled *before* it starts, so it can never start twice, even if its session was interrupted while the window is still open. A schedule saved while its window is already open starts next time. Window times follow the device time zone and DST (a start time that does not exist on a DST day moves forward). There is no schedule list screen yet; schedules can be created from the Scheduled Focus flow once the engine reports the mode as available.

**History:** every finished session (completed, interrupted, or failed to start) is written to Room with its outcome and an `EndReason` (permission lost, policy failure, device unsupported, enforcement lost, recovery used, internal error). Settings > History lists them and can clear them. The write is idempotent and repeated when the result is dismissed, in case the first attempt failed. Recovery transitions (`RecoveryPending`) exist in the state machine and engine; their screens and rules are Phase 6 (see Emergency and trusted recovery).

Tests written in Phase 4 (JVM, run in Phase 8): `SessionTimingTest`, `SessionMachineTest`, `ScheduleMathTest` (DST, midnight, overlaps), `SessionEngineTest` (start rules, failures, expiry, clock change, process death, restart, schedules), `SessionStateCodecTest`, `DraftPlanTest`, room `MappersTest`. Not covered by JVM tests: the Room DAOs and the WorkManager worker (they need an Android runtime; checked on a device in Phase 8).

## APKs and CI

Gradle ABI splits produce real, separate APKs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, plus a `universal` APK. Each ABI APK contains only its own native libraries.

`.github/workflows/build.yml` runs on every push, pull request, version tag (`v*`) and manual dispatch, with Gradle caching. It first runs the JVM unit tests (`gradle :app:testDebugUnitTest`); if one fails, no APK is built or published and the failure is in the run log. It then publishes **10 separate artifacts**:

```
fOCUS-debug-arm64-v8a    fOCUS-release-arm64-v8a
fOCUS-debug-armeabi-v7a  fOCUS-release-armeabi-v7a
fOCUS-debug-x86          fOCUS-release-x86
fOCUS-debug-x86_64       fOCUS-release-x86_64
fOCUS-debug-universal    fOCUS-release-universal
```

`.github/scripts/collect-and-verify-apks.sh` checks the APK contents, not file names: non-zero size, package id, native-library ABIs (via the zip listing), signature (`apksigner`), and exactly 5 APKs per variant.

Which one to install: `arm64-v8a` on almost all current phones, `armeabi-v7a` on old 32-bit phones, `x86`/`x86_64` on emulators, `universal` if unsure.

### GitHub Releases

After a successful build, the workflow attaches the APKs to a GitHub Release: a pushed tag `v*` (for example `v0.1.0`) makes a normal release, and every push to the default branch makes a pre-release named `build-<run number>`. Pull requests never publish. Find them under the repository's **Releases** page. If no release appears, check that the run was green (a failing unit test stops it before the release step) and that Settings, Actions, General, Workflow permissions does not block the job-level `contents: write`.

### Release signing secrets

Debug APKs need no secrets. Release APKs are built and published only if **all four** repository secrets exist; otherwise only the 5 debug APKs are published, and an unsigned release APK is never published.

1. Create a keystore (keep it private, never commit it; `.gitignore` blocks `*.keystore` and `*.jks`):
   ```
   keytool -genkeypair -v -keystore release.keystore -alias focus -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Encode it: `base64 -w0 release.keystore` (macOS: `base64 -i release.keystore`).
3. In the repository: **Settings → Secrets and variables → Actions → New repository secret**, add:

   | Secret | Value |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | the base64 text from step 2 |
   | `ANDROID_KEYSTORE_PASSWORD` | keystore password |
   | `ANDROID_KEY_ALIAS` | `focus` (or your alias) |
   | `ANDROID_KEY_PASSWORD` | key password |

Secrets are exposed only to the steps that need them. The decoded keystore lives in the runner's temp directory and is deleted at the end of the job. Pull requests from forks have no access to secrets, so they build debug APKs only.

### Downloading artifacts

Open the **Actions** tab, choose a run, and scroll to **Artifacts**. Each artifact is a zip containing one APK. The run summary lists size, ABIs, package and signer of every APK.

The workflow has not been run yet. The first real run happens in Phase 08.

## Shizuku (added after the first device test)

A normal install cannot block other apps, so Selected apps, Full focus and Scheduled focus were "Not available" on a regular phone. fOCUS can now also block apps through **Shizuku**, with no factory reset and no Device Owner:

- Install Shizuku, start it (Android 11+: Developer options, Wireless debugging), then open fOCUS, Settings, Permissions and managed device, and tap **Allow Shizuku access**.
- With Shizuku allowed, the level shows "Regular device with Shizuku" and all four modes suspend apps. This is the same `pm suspend` an adb connection runs, started through Shizuku (`device/ShizukuShell.kt`). A suspension counts only if the command reports the new state. Lock Task is not available this way (Device Owner only).
- Shizuku stops when the phone restarts. If it stops during a session, the session does **not** end: the suspensions are held by the system, the active screen says Shizuku has stopped, and checks resume when Shizuku is back (`RestrictionStatus.shizukuStopped`; a lost Device Owner still ends the session). If the end time passes while Shizuku is stopped, the journal keeps the blocked apps and the uninstall block, and the cleanup runs when Shizuku comes back (a device-change listener in `FocusApplication`) or at the next start. `suspendedAmong` throws instead of answering "none" when neither Device Owner nor Shizuku is usable, so the journal is never cleared while apps may still be suspended.
- **Stop first, then block:** before suspending, fOCUS force-stops (`am force-stop`) every app it is about to block: all apps except the protected ones in Full focus, Call only and Scheduled focus, and only the chosen apps in Selected apps. fOCUS, the Shizuku app, home, phone, emergency and settings are never stopped. A failed stop is not fatal, because suspending stops the app as well. The Shizuku app is also covered by the `no_control_apps` restriction below (no force stop or disable from Settings, Android 11+). Not covered: the Stop button inside Shizuku itself, battery managers of some manufacturers, and a computer with adb.
- **Uninstall protection:** while apps are suspended, fOCUS also sets the `no_uninstall_apps` user restriction (and `no_control_apps` on Android 11+, which also blocks force stop and "clear data" from Settings) with `pm set-user-restriction`; on a Device Owner it uses `setUninstallBlocked`. It is best effort and honest: the active screen says when it could not be confirmed. It is recorded in the write-ahead journal and removed at the end of the session; if it cannot be removed (for example Shizuku is stopped), the journal keeps it and the start-up cleanup removes it later. A computer with adb can still uninstall.
- The Shizuku app (`moe.shizuku.privileged.api`) is protected like settings and phone, so it can always be opened to restart Shizuku. Manual way out with a computer: `adb shell pm unsuspend --user 0 <package>`.
- Manifest: `ShizukuProvider` (receives the connection; not a permission request), and a `<queries>` entry for the Shizuku package. Dependencies: `dev.rikka.shizuku:api` and `provider` 13.1.5.
- Not verified yet (no build tools were available): the first CI compile, `Shizuku.newProcess` by reflection on a real device (Shizuku 13 made it private), and the `dumpsys package` output format on each Android version. Where `dumpsys` cannot be read, fOCUS falls back to what it confirmed itself.

## Restriction engine (Phase 5)

Three layers, so the logic can be tested without a device: `domain` (`DeviceCapabilities`, `ModeSupport`, `RestrictionPlanner`, the `DeviceControl` and `RestrictionJournal` ports, all pure Kotlin), `session.RestrictionCoordinator` (the real `RestrictionEnforcer`, pure Kotlin over those ports) and `device.AndroidDeviceControl` (the only code that calls `DevicePolicyManager` and `NotificationManager`).

**What each device can do** (detected on every call, never assumed):

| Mode | Device Owner | Regular device |
|---|---|---|
| Selected apps | chosen apps suspended | unsupported |
| Full focus | every app except protected ones suspended | unsupported |
| Call only | same as Full focus, plus Do Not Disturb (calls only) if access was granted | limited: notifications silenced except calls, **apps can still be opened**; needs Do Not Disturb access |
| Scheduled focus | Full focus during each window | unsupported |

"Suspended" means `DevicePolicyManager.setPackagesSuspended`: the app cannot be opened and shows no notifications until released. Protected apps (fOCUS, home, phone, emergency, settings, core system) are re-classified at start and are never suspended. On a Device Owner device fOCUS also sets a Lock Task allowlist (the protected apps; home and the power menu stay available) and the activity enters Lock Task while fOCUS is open. This is an extra layer: it is only active while fOCUS is on screen, and the app reports it as "requested", never as a guarantee. Apps installed during a Full focus session are suspended too.

**Not used:** no AccessibilityService, no overlays, no `UsageStatsManager` blocking. They cannot enforce reliably or are not allowed for this purpose, so a regular device simply cannot block apps.

**Honesty and safety rules** (covered by `RestrictionCoordinatorTest`):
- `apply` reports Applied only after the system confirmed the suspension (`isPackageSuspended`). A refused app in Selected apps fails the whole start and undoes everything; in the other modes partial results are shown on the active screen ("N apps could not be restricted"), and zero is a failure.
- Write-ahead journal (`DataStoreRestrictionJournal`): everything fOCUS may change (suspended packages, Lock Task, the previous Do Not Disturb state) is saved before it is changed and cleared only after the undo was confirmed. If the journal cannot be read or written, nothing is changed.
- `release` is idempotent and retries: whatever the system refuses stays in the journal. On every process start, if no session is running, `releaseIfNotApplied` undoes leftovers of a crash or a failed release. If the journal itself is unreadable at release time, only what the current process knows can be undone.
- Loss detection: on system events (ownership, Do Not Disturb, installed apps) and once a minute while a session runs, the real state is compared with what was applied. A withdrawn permission, lost ownership, or an app unsuspended behind fOCUS ends the session with the matching `EndReason`.
- The person's own Do Not Disturb change during a session is never overwritten.
- "Stop managing this device" (guide screen) releases everything and gives up Device Owner status; refused while a session runs.

**Setting up Device Owner:** Android only allows it on a device with no accounts, typically right after a factory reset. With fOCUS installed and USB debugging on: `adb shell dpm set-device-owner com.focus.app/.device.FocusDeviceAdminReceiver`. fOCUS cannot promote itself. The guide screen shows the detected level and refreshes when you return from system settings. Android 14+ and some manufacturers tighten provisioning further; the command may be refused, and then fOCUS stays a regular app.

**Known limits (unverified until Phase 8):**
- Suspension survives reboot and fOCUS being closed, but the end of a session is only noticed when fOCUS runs again (WorkManager is delivered "at or after"); restrictions can therefore outlast the session by a short, bounded time. The next start also cleans up.
- Behavior of suspended apps, Lock Task, Do Not Disturb policy changes and `clearDeviceOwnerApp` varies by Android version and manufacturer and was not run on any device.
- The Do Not Disturb policy is replaced during a Call only session and restored from the journal afterwards; a manufacturer that stores Do Not Disturb differently may restore it imperfectly.
- Device Owner status cannot be removed by `adb` on a normal build; use "Stop managing this device" in the app while no session runs, or a factory reset.
- Emergency calling is never blocked by fOCUS. Whether *incoming* calls ring during Call only is governed by the Do Not Disturb policy fOCUS sets (calls from anyone); this is verified on a device in Phase 8.

Tests written in Phase 5 (JVM, run in Phase 8): `ModeSupportTest`, `RestrictionPlannerTest`, `InterruptionSnapshotTest`, `RestrictionCoordinatorTest` (fakes for device, journal and apps), `DataStoreRestrictionJournalTest`. Not covered by JVM tests: `AndroidDeviceControl`, the receiver, Lock Task in the activity (checked on a Device Owner device and a regular device in Phase 8).

## Emergency and trusted recovery (Phase 6)

**Two separate paths, on purpose.**

1. **Emergency access** (Settings > Emergency, and a button on the dashboard and the active-session screen, whether or not a session runs). It opens the phone's own dialer with `112` filled in (a number most phones accept as an emergency number; you press call) and the system's emergency-information screen when the device has one. It needs no PIN, has no waiting time, is never rate-limited and is **deliberately not logged**. fOCUS places no call itself and holds no call permission. It does not end the session: the phone, home and settings apps are never restricted, so calling is never blocked in the first place.
2. **Trusted-person recovery** (the only way to end a session early; there is no ordinary unlock button). Someone you choose sets a PIN once; only they can end a running session with it.

**Setup (Settings > Trusted person).** The screen explains everything before anything is activated. You enter a name and an optional phone number (used only for a "Call" button), then hand the phone to the trusted person, who types a 6 to 12 digit PIN twice (masked; the window is excluded from screenshots, recordings and the recent-apps preview with `FLAG_SECURE`). Only a salted PBKDF2-HMAC-SHA256 hash is stored (310,000 iterations, stored per hash so it can be raised later); fOCUS cannot show the PIN to anyone. Nobody is contacted: there is no internet permission and nothing is sent. The confirm screen of every session says before it starts whether anyone can end it early.

**Ending a session.** "End with trusted person" appears on the active-session screen only when a PIN exists. It moves the session to `RecoveryPending` (restrictions unchanged, time keeps running). A right PIN ends the session, releases the restrictions and records it in history as ended through recovery. Backing out returns to Active. The PIN works **once**: afterwards the person is remembered but must set a new PIN before they can end another session. The session is ended first and the PIN used up afterwards, so a crash between the two can never trap anyone (worst case the PIN works once more).

**Rate limit.** Three wrong PINs are free; then entry is locked for 1, 5, 15, 30, then 60 minutes after each further wrong PIN. The count is stored, so closing or killing the app does not reset it, and it is shared with the settings screen (changing or removing the trusted person asks for the PIN too). The lock runs on the monotonic clock within one boot, so changing the clock or time zone does not shorten it; after a restart it falls back to the wall clock, so a clock set forward while the device was off cannot be detected (same limit as session timing). The lock never touches emergency access.

**What recovery can and cannot override.** It ends the running session and lifts its restrictions. It does not cancel schedules for later days, does not remove Device Owner status or the app, and is not a bypass for anything else. Trusted-person settings and the audit log cannot be changed while a session runs.

**Forgotten PIN.** "Request removal" starts a 24-hour wait (same two-clock rule). When it is over and no session is running, the trusted person can be removed without the PIN. The request can be cancelled and is recorded.

**If there is no trusted person.** The session can only end when its time is up (at most 24 hours; emergency calling stays available). This is stated before starting and on the active screen. It is the owner's choice and is not hidden.

**Crash, restart, permission loss.** `RecoveryPending` is stored like any session state, so after a process death or restart it comes back, restrictions are re-applied, and the PIN still works. If a permission or the device policy is lost while recovery is pending, the engine ends the session as interrupted (the PIN is not used up). If the session reaches its end time while recovery is pending, it completes normally. If the recovery data cannot be read, no PIN can be checked and the screens say so; the session then ends at its time. A recovery that is left open (for example the app was closed) simply stays pending: nothing is released.

**Audit log** (Settings > Recovery log). Local only, newest 200 events: trusted person set, changed, removed; PIN replaced; removal requested or cancelled; recovery opened, cancelled, succeeded, failed or locked. It records what and when, never a PIN, name or number, and never emergency use. It can be cleared only while no session runs. A failed log write never blocks recovery.

**Honest limits (unverified until Phase 8).** A 6-digit PIN has only a million values; the hash helps only against someone who can already read the app's private storage, and the real protection is the lockout. If the trusted person tells the PIN over the phone, the person holding the phone learns it; the PIN is single use, but the better practice is for them to be present. A person with `adb`, a factory reset or root is outside what any app can prevent. Typed-PIN screens and `FLAG_SECURE` behavior are checked on a device in Phase 8.

Tests written in Phase 6 (JVM, run in Phase 8): `RecoveryPoliciesTest` (PIN, name, phone, lockout schedule, two-clock timer), `Pbkdf2PinHasherTest`, `RecoveryServiceTest` (against the real `SessionEngine`: right and wrong PIN, lockout, clock change, restart, permission loss, expiry, unreadable storage, failing audit, removal wait), `DataStoreRecoveryRepositoryTest`, `DataStoreAuditLogTest`. Not covered by JVM tests: the Compose screens, `FLAG_SECURE` and the dialer intents.

## Statistics, optimization, testing (Phase 7)

### Statistics (Settings > Statistics)

Real numbers from the session history, nothing else, and nothing new is stored: **time restricted**, how many sessions **completed**, **ended early** (and, only if there were any, **did not start**), the last **7 days** and the last **8 weeks**. Each day or week is a label, the time written out, and a thin bar for a quick glance (the text carries the meaning, so large text sizes and screen readers read it in full; each row is announced as one item). With no history it says so instead of showing zeros.

How it is calculated (`domain/Statistics.kt`, pure Kotlin):

- **All-time totals** are counted by the database (`GROUP BY outcome`), so they cover everything stored. The History screen still lists only the newest 200 sessions.
- **Days and weeks** come from one indexed query for the last 8 weeks. Weeks run Monday to Sunday, like the schedule screen.
- A session counts from its start for the time its restrictions were really in force, not until the moment the app noticed it had ended. A session that runs across midnight (or across Sunday into Monday) is **split at local midnight**, DST days included. Sessions that never started count as no time.
- While the screen is open the numbers update when history changes and move on after local midnight. The queries and the midnight timer run only while the screen is started.
- Clearing the history clears the statistics.

**Not built, on purpose: "most-restricted apps".** History stores only how many apps a session restricted, not which ones. Showing a ranking would mean storing the package names of every session (new per-app data and a schema change), and it could only cover Selected Apps sessions, since Full Focus has no list. The brief allowed it only with little data collection, so it is left out. If you want it anyway, it is a small, contained addition (one column and a migration).

### Optimization and lifecycle

- **Database:** version 2 adds an index on `session_history.endedAtMillis` (newest-first list and the statistics window). The migration only creates the index and touches no data; there is no destructive fallback, so a failed migration can never silently wipe history.
- **Battery:** the clock on the dashboard, active-session, review and duration screens used to keep ticking while the app was in the background (a composition stays alive there). `rememberNowMillis` now runs only while the screen is started and sets the current time first when it resumes. Already fine and unchanged: the once-a-minute loss check is an in-process timer (no wake lock, no alarm), there is one unique WorkManager request, and package-change and policy receivers exist only while their screen or a session needs them.
- **Memory:** the app-icon cache is limited by bytes (6 MiB) instead of by number of icons, so it no longer grows with screen density.
- **Layout:** on wide windows (tablets, foldables, landscape) screen content is centered and limited to 600 dp instead of stretching text lines across the screen. Phones in portrait look as before.

### Found in review and fixed

- If the history database could not be read, the error went uncaught into the view-model scope and ended the app. The History and Statistics screens now show "could not be read" with **Try again**; a failed **Clear history** says so.
- An uncaught error in the start-up or on-resume `reconcile()` would have ended the process on every launch. Both are now guarded; the next return to the app retries, and the engine still never shows a session as running that it could not confirm.

### Tests written (JVM, run in Phase 8)

`StatsCalculatorTest` (empty history, one day, across midnight, across Sunday, failed and interrupted sessions, window edges, DST spring-forward and fall-back, window start), `ObserveFocusStatsTest` (window query, live update, day change), and two new `MappersTest` cases for the per-outcome totals. Not covered by JVM tests: the Room queries and migration, the Compose screens and `StatsViewModel` (they need a device or an extra test dependency). No UI test framework was added: it would be a new dependency that cannot be checked before Phase 8.

### Checklist for the Phase 8 device run

- **Screens:** small phone (about 320 dp wide), normal phone, tablet and a foldable, portrait and landscape; rotate on Statistics, History and the active screen.
- **Text and accessibility:** font size at the largest setting and display size at the largest; TalkBack through Statistics, History, mode and app lists; contrast in dark and light.
- **Android versions:** API 26 (minimum), a mid release (10 to 12), 14 and the newest (target 36); Device Owner and regular device.
- **Error cases:** Statistics and History with no data, with data, with the database unreadable (cannot be forced without a rooted device; check the code path), clear history; time zone and clock changes; midnight with Statistics open; a session ending while the app is closed; process death during a session.
- **Battery:** a long session with fOCUS in the background; the device should show no wake-ups from fOCUS apart from the one reconcile request.

## Phase 8: what was checked and what is still open

### Checked by reading and scripts (no build tools were available)

- **Dependencies:** every library in `libs.versions.toml` is used by the code (DataStore, Room with KSP, WorkManager, Navigation, Lifecycle, Compose). Nothing was removed because nothing is unused. Test code uses JUnit only.
- **Versions:** AGP 9.2.1 exists and requires Gradle 9.4.1 or newer and build-tools 36.0.0; its own Kotlin plugin dependency is 2.3.10, which is what the catalog uses. WorkManager 2.12.0 is a stable release (September 2026) with minSdk 24, below this app's 26. KSP 2.3.9, Room 2.8.5 and Compose BOM 2026.11.01 are still only taken from release pages, not from a build.
- **Resources:** all 293 string resources used in code or XML exist, with no duplicates. One unused string (`result_planned`) was removed.
- **Structure:** every Kotlin file's `package` matches its folder; every `com.focus.app` import in main and test code resolves to a declaration; the manifest's classes and the `device_admin.xml` resource exist.
- **Leftovers:** no TODO, FIXME, stub or logging calls in main code. One style fix: `AndroidDeviceControl.setLockTaskAllowlist` no longer uses `!!` (the null check now smart-casts).
- **CI gap fixed:** the workflow only built APKs and never ran the unit tests. It now runs `gradle :app:testDebugUnitTest` before building (no extra artifact is uploaded, so the count stays at 10).
- **Layers:** `domain`, `session` and `recovery` import no Android classes, so their tests can run on the plain JVM; `data` uses only DataStore (and Room for the database classes, which no JVM test touches). Tests use only JUnit, coroutines core and DataStore, all already on the test classpath.
- **DSL:** the `splits { abi { isEnable, reset, include, isUniversalApk } }` block matches the current Android documentation.
- **Reviewed for logic, nothing wrong found:** `SessionEngine` (locking, restore, expiry, schedules), `RestrictionCoordinator` (write-ahead journal, idempotent apply, release, loss checks; no lock-order problem between the engine and the coordinator), `AndroidDeviceControl` (API-level guards for Do Not Disturb, suspension and Lock Task), the Room schema and the 1→2 migration, resources (theme, icon, `device_admin.xml`).
- **Privacy:** no `INTERNET` permission, `allowBackup="false"`, no logging, PIN stored only as a salted PBKDF2 hash, `FLAG_SECURE` on PIN screens, no `QUERY_ALL_PACKAGES`.

### Still open (cannot be done without a machine that can build)

1. **Gradle wrapper:** `gradlew`, `gradlew.bat` and `gradle-wrapper.jar` are still missing (see Build). CI does not need them.
2. **Clean build and unit tests:** `gradle :app:testDebugUnitTest :app:assembleDebug`. Nothing has been compiled yet, so compile errors are possible anywhere, including in the 29 test files.
3. **First CI run:** push to GitHub and read the run summary; fix whatever fails. The release APKs need the four signing secrets.
4. **APK checks:** the CI script checks size, package id, ABIs and signature. Version (`versionName` 0.1.0, `versionCode` 1), installability and the merged permission list still need a look, for example with `aapt2 dump badging` / `aapt2 dump permissions` and an install on an emulator.
5. **Device run:** the checklist in the Phase 7 section above.
6. **Native library assumption:** the per-ABI APKs only exist if some dependency ships native code (expected: `libandroidx.graphics.path.so` through Compose). If none does, Gradle may produce only the universal APK and `collect-and-verify-apks.sh` will fail with "must contain only '<abi>' native libs, found: 'none'". That would be a real finding about the build, not a script bug.
7. **R8:** release builds use minification and shrinking with no custom keep rules. Run a release build and open every screen; add keep rules only if something breaks.

## Phases

| # | Phase | State |
|---|---|---|
| 01 | Foundation | written |
| 02 | UI system and core screens | written, approved |
| 03 | App discovery | written, approved |
| 04 | Session engine and timer | written, approved |
| 05 | Restriction engine | written, approved |
| 06 | Emergency and trusted recovery | written, approved |
| 07 | Statistics, optimization, testing | written, approved |
| 08 | Finalization and release (first build, tests and CI run) | static review done; build, tests, CI run open |
