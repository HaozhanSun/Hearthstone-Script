# Screen recovery evidence and gotchas

## Required evidence

Every `ScreenStateRecovery` trigger attempts a screenshot before safety gates
and records one structured log trail containing `runId`, `sessionId`, trigger,
elapsed time, observed/target state, provider, action or block reason, and the
exact screenshot path. `STARTUP_PROBE` is observation-only; it must never send
input. `TIMEOUT_RECOVERY` may dispatch a mode recovery only after the state is
still current and the detector confidence gate passes.

The important log events are:

- `SCREEN_RECOVERY_CAPTURE`
- `SCREEN_RECOVERY_TRIGGER`
- `SCREEN_RECOVERY_OBSERVATION`
- `SCREEN_RECOVERY_ACTION_GATE`
- `SCREEN_RECOVERY_ACTION`
- `SCREEN_STATE_CORRECTED`
- `SCREEN_RECOVERY_FAILED` or `SCREEN_RECOVERY_UNRESOLVED`

When a screen is recognized as `HOME` while the mode is already `HUB`,
`Mode.recover(..., enterStrategy=true)` explicitly re-enters the HUB strategy.
Without that branch, canceling stale work while leaving the mode unchanged can
produce a visible HOME screen with no follow-up action.

## Retention and safety

The compact ring is `log/debug-screenshots`. It is the only directory managed
by the retention policy. Defaults are 60 files, 256 MiB total, and a 1.5 second
write cooldown. The persistent keys are:

- `SCREEN_RECOVERY_SCREENSHOT_MAX_FILES`
- `SCREEN_RECOVERY_SCREENSHOT_MAX_BYTES`
- `SCREEN_RECOVERY_SCREENSHOT_COOLDOWN_MS`

Oldest files are evicted first and each eviction emits
`DEBUG_SCREENSHOT_EVICTED` with size, limits, and path. Writes are serialized
so concurrent recovery/watchdog captures cannot corrupt the retention pass.
Unknown-state archives, run ledgers, final-victory evidence, user `config`,
`data`, `log` history outside this directory, `plugin`, and backups are outside
this deletion boundary.

## Gotchas

- A trigger is not an action. Always inspect `SCREEN_RECOVERY_ACTION_GATE` to
  tell whether safety, state freshness, startup policy, or a missing strategy
  blocked input.
- `Mode.currMode == HUB` does not prove the HUB strategy is active. Recovery
  must request strategy re-entry when the visible screen is HOME.
- A throttled screenshot is an explicit diagnostic outcome, not evidence that
  the screen was captured. Use the preceding `DEBUG_SCREENSHOT_THROTTLED` log.
- Do not use an old debug screenshot or old Power.log with a new run ledger.
  Provider, screenshot, Power.log marker, PID, and build hash must share the
  same run id.
