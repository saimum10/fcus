# device

Android platform adapters (PackageManager, UsageStatsManager, DevicePolicyManager,
Lock Task) implementing `domain` interfaces. Capability detection lives here.
Depends on `domain`.

- `PackageInstalledAppsSource`: launchable apps via `PackageManager` and the manifest
  `<queries>` (no `QUERY_ALL_PACKAGES`); detects the home, phone, emergency and settings
  apps for `ProtectionPolicy`; reports install/remove/update through a dynamically
  registered package-change receiver.
- `PackageAppIconSource`: renders icons to pixels with a small LRU cache.
- `AndroidTimeSource`: wall clock, `elapsedRealtime`, boot counter.
- `WorkManagerWakeScheduler` + `ReconcileWorker`: one unique, replaceable wake-up.
- `AndroidDeviceControl`: the only user of `DevicePolicyManager` (suspend apps, Lock Task
  allowlist, give up ownership) and `NotificationManager` (Do Not Disturb with save and
  restore of the previous state). Detects the deployment level on every call. Blocking
  calls: never on the main thread.
- `FocusDeviceAdminReceiver`: lets Android make fOCUS the Device Owner during provisioning;
  requests no policies and does nothing by itself.
- `ShizukuShell`: the only class that talks to Shizuku; runs `pm suspend` / `pm unsuspend` / `dumpsys package` with shell rights. Used by `AndroidDeviceControl` when fOCUS is not Device Owner but Shizuku is running and allowed.
- `NoRestrictionEnforcer`: the truthful stand-in with no engine (tests); unused by the app since Phase 5.
