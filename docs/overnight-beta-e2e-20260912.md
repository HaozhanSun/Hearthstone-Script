# Overnight Beta E2E ledger — 2026-09-12

## Rejected lineage

- Artifact: `hs-script_v4.16.309-local-20260912-040052PDT.jar`
- Java PID: `44228`; Hearthstone PID: `34768`
- Power.log: `D:\Hearthstone\Logs\Hearthstone_2026_09_12_04_10_30\Power.log`
- Result: rejected. Power.log contained current-player `PLAYSTATE=CONCEDED` and
  `PLAYSTATE=LOST`, while the app emitted `TERMINAL_RESULT_CLASSIFIED
  outcome=draw-or-unknown authoritative=false`. The result handler had reduced
  the typed terminal marker to a Boolean and then lost the distinction during
  the E2E milestone gate.

## Iteration 1 — fresh Beta lineage

- Channel: Beta
- Artifact: `hs-script_v4.16.310-local-20260912-065127PDT.jar`
- Deployment ID: `hs-script_v4.16.310-local-20260912-065127PDT.jar|94dd11939eee965f`
- Artifact SHA-256: `94dd11939eee965fb9216a11b13e68982a5adbe64790fa0c6a04aabb8bf18c46`
- Beta JVM PID: `48756`, started `2026-09-12 07:05:21 PDT`
- Hearthstone PID: `44284`, started `2026-09-12 07:07:18 PDT`
- Power.log: `D:\Hearthstone\Logs\Hearthstone_2026_09_12_07_07_21\Power.log`
- Beta application log: `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta\log\hs_script.log`

### Fix and offline evidence

The result path now has a typed Power.log terminal reader and preserves
`CONCEDED`/`LOST` whenever the authoritative marker exists, so a fast surrender
cannot be rendered as `draw-or-unknown`. Focused tests passed (7/7), followed by
the complete release suite (149/149, 0 failures/errors/skips).

### Fresh-lineage evidence so far

- `MULLIGAN_UI_READY` and three accepted mulligan clicks were recorded for game 1.
- `MULLIGAN_STATE=DONE` and the `post-confirm` screenshot were recorded.
- `LIFECYCLE_STATE` is `working=true`, `inWar=true`, `warPhase=GAME_TURN`.
- `OCR_PROVIDER_HEALTH provider=PADDLEX ok=true` was recorded.
- The new lineage has not yet passed the two-game/no-regression gate.

## Iteration 2 — fresh Beta lineage after quest-reward fix

- Fix: added the verified `SW_028t5` identity and a narrow named opaque-minion
  play fallback for `船长洛卡拉`, preventing the quest reward from being treated
  as an unrecognized own card.
- Focused evidence: 17/17 card identity/action tests passed after correcting the
  test assertion; the release suite then passed 149/149 with 0 failures/errors/skips.
- Channel: Beta
- Artifact: `hs-script_v4.16.311-local-20260912-073140PDT.jar`
- Deployment ID: `hs-script_v4.16.311-local-20260912-073140PDT.jar|10ad444684f031c5`
- Artifact SHA-256: `10ad444684f031c53834771848d21bc637f3f744d73715d8a37f8653a8a61645`
- Beta JVM PID: `60388`, started `2026-09-12 07:39:33 PDT`
- Hearthstone PID: `46348`
- Power.log: `D:\Hearthstone\Logs\Hearthstone_2026_09_12_07_40_52\Power.log`
- Beta application log: `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta\log\hs_script.log`

### Fresh-lineage evidence so far

- `SCHEDULE_RUNTIME_SNAPSHOT` confirmed paired deck slot 2 / `海盗战 V2.2 · build 1.1.7`.
- `AUTO_START_DECISION ... decision=true` and the 30-minute debug lease were recorded.
- `PADDLEX_OCR_SIDECAR_STARTED` and `OCR_PROVIDER_HEALTH ... ok=true` were recorded.
- Mulligan and turn execution completed; input foreground checks confirmed the
  Hearthstone PID and `LIFECYCLE_STATE ... inWar=true ... myTurn=false` followed.
- `MCTS_LETHAL_TELEMETRY` and round screenshots are present for the fresh game.
- The quest-reward fallback is offline-verified but has not yet been observed in
  a live game state; the two-game/no-regression gate remains open.

## Iteration 3 — fresh Beta lineage after terminal-result race fix

- Live finding: v4.16.311 Power.log recorded the current player as `WON` and the
  opponent as `CONCEDED/LOST`, but the app classified `draw-or-unknown` because
  the terminal callback briefly had the opponent model marker before
  `war.me.gameId` was populated.
- Fix: when the player identity is unresolved, the result handler now waits for
  the typed current-player Power.log marker instead of trusting the stale model
  terminal. Focused result tests passed 7/7; the full release suite passed
  149/149 with 0 failures/errors/skips.
- Channel: Beta
- Artifact: `hs-script_v4.16.312-local-20260912-080111PDT.jar`
- Deployment ID: `hs-script_v4.16.312-local-20260912-080111PDT.jar|c7640f45c2b49b2b`
- Artifact SHA-256: `c7640f45c2b49b2bc12358600b88efaced9db2d178e242d8942c481492878099`
- Beta JVM PID: `15640`, started `2026-09-12 08:09:00 PDT`
- Hearthstone PID: `31352`, started `2026-09-12 08:10:51 PDT`
- Power.log: `D:\Hearthstone\Logs\Hearthstone_2026_09_12_08_10_54\Power.log`
- Beta application log: `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta\log\hs_script.log`

### Fresh-lineage evidence so far

- Paired schedule selection, auto-start, PaddleX sidecar health, rank preflight,
  mulligan input, post-confirm screenshot, and live Hearthstone foreground
  confirmation are present for game 1.
- Current v4.16.312 state is `inWar=true`, `SPECIAL_EFFECT_TRIGGER`; the run is
  continuing normally. Result and quest-reward live checks remain pending.

## Iteration 4 — fresh Beta lineage after terminal flush race fix

- Live finding: v4.16.312 still classified one game as `draw-or-unknown` while
  the same Power.log already contained the current player's `PLAYSTATE=WON`.
  The callback accepted the stale in-memory opponent terminal because the
  Power.log marker had not finished flushing at the first callback.
- Fix: when there is no local surrender request, a model-only terminal marker is
  never accepted as authoritative; the result handler waits for the typed
  Power.log marker and only falls back after the bounded wait.
- Focused terminal tests passed 7/7; the complete release suite passed 149/149
  with 0 failures/errors/skips.
- Channel: Beta
- Artifact: `hs-script_v4.16.313-local-20260912-103507PDT.jar`
- Deployment ID: `hs-script_v4.16.313-local-20260912-103507PDT.jar|b2c758211f122804`
- Artifact SHA-256: `b2c758211f122804b0ffde19493ca09d7136bbe9e7c392ca1ac8b4db7380ae8b`
- Beta JVM PID: `68212`, started `2026-09-12 10:54:53 PDT`
- Hearthstone launch attempt: `D:\Hearthstone\Hearthstone_2026_09_12_10_54_51\Power.log`
- Beta application log: `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta\log\hs_script.log`

### Fresh-lineage evidence so far

- v4.16.313 startup logged the exact new build and deployment configuration.
- PaddleX persistent sidecar health completed successfully (`ok=true`).
- The 30-minute debug lease was enabled by default.
- The app remained alive and reported `working=true`.
- Hearthstone created a fresh Power.log but its game process exited before the
  log became readable. Recovery correctly recorded
  `SCREEN_RECOVERY_DEFERRED reason=game-foreground-unconfirmed` and the window
  handle later became invalid. No new game is accepted from this lineage.

## Acceptance gate

- [x] Fresh deployed artifact and new process lineage
- [ ] Beta and Hearthstone alive after restart
- [ ] Fresh Power.log attached and being consumed
- [ ] Two consecutive newly executed games in this lineage
- [ ] At least one current-build player `PLAYSTATE=WON` marker paired with the accepted script result
- [ ] No accepted-chain exception, timeout, OCR failure, or unhandled `UNKNOWN`
- [ ] Two-hour log/screenshot correlation checkpoint
- [ ] Overnight stability checkpoint

## Next action

Continue from v4.16.313 after restoring a live Hearthstone process, then inspect
the first two games and matching screenshots. The result-classification fix is
not accepted online until a current-build Power.log marker and matching script
result are observed in the same fresh lineage. The overnight automation remains
active for the next checkpoint.
