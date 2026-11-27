# session

`SessionEngine`: drives the session state machine (`domain.SessionMachine`), persists the
state, tracks time on the monotonic and wall clocks (`domain.SessionTiming`), recovers after
process death and restarts, runs schedules, and writes history.
Depends on `domain` interfaces only (never on `device` or `data` implementations directly);
the composition root (`FocusApplication`) injects the store, history, schedules, enforcer,
time source and wake scheduler.

Rules: everything runs under one mutex; Active is published only after the enforcer applied
the restriction and the state was stored; one session at a time; no overlap with schedules.

`RestrictionCoordinator` (Phase 5): the real `RestrictionEnforcer` and the `DeviceManagement`
the UI reads. Pure Kotlin over `DeviceControl`, `RestrictionJournal` and `InstalledAppsSource`.
Write-ahead journal, confirmed (never assumed) suspension, retrying release, loss detection,
start-up cleanup (`releaseIfNotApplied`).
