# data

Local persistence only (Room, DataStore) implementing `domain` repository interfaces.
No network. Depends on `domain`.

- `DataStoreOnboardingRepository`: the onboarding flag.
- `DataStoreAppSelectionRepository`: the chosen package names (a small string set, its own
  DataStore file). Room arrives in Phase 4 with session history.
- `DataStoreSessionStore` + `SessionStateCodec`: the current session state as one atomic value.
- `DataStoreRestrictionJournal`: write-ahead record of what the restriction engine may have
  changed (package names, previous Do Not Disturb numbers); its own DataStore file.
- `DataStoreRecoveryRepository`: the trusted person, the salted PIN hash and the wrong-PIN counter and timers as one atomic value; its own DataStore file. A damaged or partial record is reported unreadable, never read as "no PIN".
- `DataStoreAuditLog`: the newest 200 recovery events (id, time, type, session id) in its own DataStore file; no PIN, name or number.
- `room/`: `FocusDatabase` (session history, schedules). Version 2: Phase 7 added an index on `session_history.endedAtMillis` (migration 1 to 2 only creates it) and two queries for statistics (per-outcome totals, rows since a time). No destructive fallback; any schema change must ship a migration.
