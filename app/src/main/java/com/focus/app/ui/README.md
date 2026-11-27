# ui

Jetpack Compose screens, ViewModels, navigation, theme. Talks to `domain`, `session`
and `recovery` APIs; never imports `data` or `device` implementations directly (the
composition root `FocusApplication` hands the UI `domain` interfaces).

- `theme/`       palette, type scale, spacing, shapes, `FocusTheme`
- `components/`  shared building blocks (screen frame, buttons, rows, chips)
- `format/`      time/duration formatting and the clock tick helper
- `state/`       `AppViewModel` (onboarding, session status), `SessionDraftViewModel`, `HistoryViewModel`, `StatsViewModel` (both turn a storage error into a state instead of crashing)
- `FocusDependencies`  the `domain` interfaces the UI receives from the composition root
- `navigation/`  routes and the NavHost
- one package per screen: `onboarding`, `home`, `guide`, `mode`, `apps` (list, search,
  icons, `AppSelectionViewModel`), `duration`, `schedule`, `confirm`, `active`, `settings`, `history`, `stats` (statistics from local history),
  `emergency` (emergency screen and dialer intents), `recovery` (trusted person, end session
  early, recovery log, PIN field and `FLAG_SECURE` helper)
