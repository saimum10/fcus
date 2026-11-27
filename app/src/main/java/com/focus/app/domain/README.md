# domain

Pure Kotlin. **No Android imports.**
Models (modes, durations, session drafts, discovered apps), repository and source
interfaces, and the rules that need no Android (`DurationPolicy`, `ProtectionPolicy`,
`AppListLogic`). Also the capability interfaces that will separate *regular device* from
*Device Owner* behavior (Phase 5).
Depends on nothing else in the app.

App discovery (Phase 3): `InstalledAppsSource`, `AppIconSource`, `AppSelectionRepository`,
`DiscoveredApp`, `ProtectionReason`. Icons cross the boundary as raw ARGB pixels
(`AppIconBitmap`) so this package never imports `android.graphics`.

Session engine (Phase 4): `SessionState`, `SessionEvent`, `SessionMachine` (the legal
transitions), `SessionTiming` (remaining time across clock changes and restarts),
`Schedule`/`ScheduleMath`, the ports (`SessionStore`, `SessionHistoryRepository`,
`ScheduleRepository`, `TimeSource`, `WakeScheduler`, `RestrictionEnforcer`) and the
`SessionController` the UI uses.

Restriction engine (Phase 5): `DeviceCapabilities` and `DeploymentLevel`, `ModeSupport` (the one
table of what each mode can do on each kind of device), `RestrictionPlanner` (what to suspend;
protected apps never), the ports `DeviceControl`, `RestrictionJournal`, `DeviceManagement` and
`LockTaskSignal`, and `Capability.Limited` for partly enforceable modes.

Recovery (Phase 6, `Recovery.kt`): `TrustedPerson`, `PinCredential`, `RecoveryRecord`, `WaitTimer` (a wait measured on two clocks), `PinPolicy`, `TrustedPersonPolicy`, `AttemptPolicy` (the lockout schedule), the ports `RecoveryRepository`, `PinHasher`, `AuditLog`, and the `RecoveryController` the UI uses with its `RecoveryStatus` and `RecoveryResult`.

Statistics (Phase 7, `Statistics.kt`): `HistoryTotals`, `FocusStats`, `StatsCalculator` (day and week buckets from history; splits at local midnight; takes the time and zone as arguments) and `observeFocusStats` (the live flow, which re-queries after each midnight). `SessionHistoryRepository` gained `observeTotals` and `observeEndedSince`.
