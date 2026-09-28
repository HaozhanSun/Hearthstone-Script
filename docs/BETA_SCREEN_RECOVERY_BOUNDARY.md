# Beta screen-recovery boundary

Comparison ref: `origin/main` at `c4a6e91ba61ac52968d71539a6c70550be572c13` in `HaozhanSun/Hearthstone-Script`.

## Baseline behavior (not disabled by the Beta switch)

| Path | Baseline owner | OFF behavior |
| --- | --- | --- |
| 10-second lifecycle heartbeat, 30-second `GAME_OVER` and stale-state fallback | `LifecycleTrace` | Runs as upstream; baseline `ScreenStateRecovery.inspectAndRecover` route remains available. |
| Startup screen probe after game-window discovery | `GameStarter.scheduleStartupScreenProbe` | Runs with the upstream 2.5-second delay, 15-second bound, and 2-second retry interval. |
| Known-screen capture/OCR/classification/recovery and result-page postcheck | `UpstreamScreenStateRecovery` (upstream-compatible copy) | Remains available to the baseline lifecycle/startup callers. |
| Repeated surrender screen watchdog | `ScreenWatchdog`, controlled by existing `SCREEN_WATCHDOG_ENABLED` | Keeps the upstream per-feature setting; Beta global switch does not suppress its capture/OCR. |
| Stale result-page dismissal and result postcheck | `GameUtil.dismissStaleGameEndScreen` | Remains usable by lifecycle, gameplay-mode, and result-recovery callers. |
| Power.log-driven mode/phase/match progression, strategy and MCTS | Existing listeners and strategies | No dependency on the Beta recovery runtime or its flag. |

The baseline and optional screen paths use Robot capture in `UpstreamScreenStateRecovery.captureScreen`, `ScreenWatchdog.captureScreen`, and `ScreenStateRecovery.captureScreen`. `GameUtil`'s result postcheck routes to the upstream implementation while the Beta flag is OFF. Strategy/MCTS screenshots, rank/mulligan OCR, and ordinary result evidence are outside this switch.

## Beta-only optional behavior (requires `BETA_RECOVERY_EXTENSIONS_ENABLED=true`)

- `BetaScreenRecoveryService` owns the added no-progress polling, process/log rebind/restart/pause decisions, foreground-failure escalation, startup-handoff supervisor, retry context, and delayed callbacks.
- `ScreenStateRecovery.inspectAndRecover` is a compatibility façade: OFF routes to the upstream-compatible baseline object; ON routes to the Beta extended pipeline (`inspectBetaAndRecover`).
- The runtime listens to `ConfigUtil` changes, increments a generation, cancels tracked Beta futures on OFF, and rejects stale callbacks before work can affect input or state.
- Existing baseline result helpers and watchdogs are not gated. Beta terminal/foreground handling only applies to the Beta pipeline while enabled.

This setting is deliberately not a total screen-recovery kill switch. `SCREEN_WATCHDOG_ENABLED` independently controls the upstream surrender watchdog.

Tests should therefore assert two distinct facts: OFF prevents Beta service work and cancels queued Beta callbacks; OFF does not disable the upstream lifecycle/startup/result paths.
