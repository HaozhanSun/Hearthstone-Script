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

- After authoritative same-game terminal proof, a fresh exact-window capture may use the calibrated lower-center Continue band before OCR. This lets Victory variants with a visible Continue control remain `UNKNOWN` without waiting on slow full-screen OCR; it permits only the bounded result sequence and never infers win/loss/rank. Revalidate that the authorized frame is no older than 25 seconds at dispatch, matching the bounded observer stall budget.
- Missing process state can make `WarEx.inWar` and phase latches stale. Clear those fences only when the game process is absent and the mandatory surrender is no longer pending with terminal cleanup proof retained; never clear a pending surrender or infer terminal proof from an empty/unbound Power.log.
- When diagnosing latency, separate OCR provider duration from time until an input or state transition. Health polling at 6–38 seconds is not by itself user-visible delay; repeated `terminal-result-cleanup-pending` blocks before the coordinator starts are user-visible. Record first block, coordinator start, and confirmed destination as separate timestamps.
- Beta v4.16.679 report (Power.log session `Hearthstone_2026_10_24_19_05_28`): gen2 first cleanup block 19:13:23.161 to coordinator start 19:14:55.304 = 92.1s, destination 19:15:44.301 = 141.1s total; gen4 19:22:26.218 to 19:23:10.655 = 44.4s, destination 19:23:56.856 = 90.6s total; gen6 19:32:41.274 to 19:33:56.817 = 75.5s, destination 19:34:37.148 = 115.9s total; gen10 19:50:30.810 to 19:51:54.283 = 83.5s, destination 19:52:33.751 = 122.9s total. These intervals identify the pre-coordinator cleanup gate as the primary user-visible wait; coordinator-to-destination still takes 39.5–49.0s. The source log was not present locally during this port, so these exact supplied intervals are recorded as incident evidence and were not independently replayed here.

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
