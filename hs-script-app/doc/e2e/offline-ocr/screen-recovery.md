# Screen recovery evidence and gotchas

- The HUB mode chooser's four stacked parchment buttons are a structural visual signature. Its real parchment faces can be brown and dim, so calibrate the pixel threshold against captured client frames instead of requiring bright-neutral pixels. Classify that signature before interpreting global OCR for deck or pack text, because text from unrelated regions can be noisy or stale. During the first 60 seconds after a matchmaking Start dispatch, recovery may accept positive matchmaking/game progress but must not change modes from menu OCR; this prevents a slow startup probe from overwriting the in-flight mode-entry sequence. Preserve the one authorized Traditional Battle entry action, allow queue start without rank preflight, and keep rank enforcement post-mulligan.

## Required evidence

- The Beta upstream stale-screen probe must populate its visual signature with
  `StartupQuestOverlayVisualClassifier` and let that exact event-quest match
  outrank OCR and background deck/menu labels. Regression-test both the captured
  live event-quest frame and the shared recovery dispatch callsite; if the
  classifier is false, `SCREEN_RECOVERY_UNRESOLVED` must include the probe
  purpose, classifier result, and frame dimensions. Never send top-left clicks
  or restart unless a fresh same-window capture confirms the exact modal and a
  safe HUB or current-session Tournament `FILL_DECK` context. Keep the three
  click attempts, 60-second re-probe stage, and active-game/queue/result/pause
  fences covered by deterministic tests. For current-session Tournament
  `FILL_DECK`, the 15-minute matchmaking input-intent history alone is not
  active queue evidence; only a recently observed matchmaking screen, the
  60-second input race guard, or current `matchmakingSearchVisual` blocks.

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

The compact ring is `debug-screenshots` under Beta's selected artifact root;
non-Beta channels retain `log/debug-screenshots`. It is the only directory
managed by the retention policy. Defaults are 60 files, 256 MiB total, and a
1.5 second write cooldown. The persistent keys are:

- `SCREEN_RECOVERY_SCREENSHOT_MAX_FILES`
- `SCREEN_RECOVERY_SCREENSHOT_MAX_BYTES`
- `SCREEN_RECOVERY_SCREENSHOT_COOLDOWN_MS`

Oldest files are evicted first and each eviction emits
`DEBUG_SCREENSHOT_EVICTED` with size, limits, and path. Writes are serialized
so concurrent recovery/watchdog captures cannot corrupt the retention pass.
Unknown-state archives, run ledgers, final-victory evidence, user `config`,
`data`, `log` history outside this directory, `plugin`, and backups are outside
this deletion boundary.

## Beta result and storage recovery

- For Beta `script.ini`, configure ini4j escaping before loading the file, then
  keep its backing file attached for the normal store cycle. Windows path
  separators and spaces must survive read/store/reload unchanged. If either
  startup path is invalid, recover only `GAME_PATH` and `PLATFORM_PATH` from a
  single unambiguous legacy backup pair whose game directory and platform
  executable both exist. Never infer separators from damaged values. Invalid
  Beta startup paths must stop the bounded platform retry stage without
  pausing; show the repair action and wait for an explicit Start retry. Keep
  path validation, backup recovery, and retry stopping Beta-only.
- After complete current-game terminal proof, a fresh authorized
  `DECK_SELECTION` or `MATCHMAKING` observation may clear the Beta queue-input
  fence only after the mandatory surrender and terminal-cleanup guards are
  clear and no game is active. Keep the Power.log terminal-tail fence until a
  new `CREATE_GAME`; clearing queue-input recovery must not release stale-tail
  parsing or add a pre-mulligan rank gate.
- In terminal result cleanup, classify the centered game-menu overlay before
  destination detection or Continue-band dispatch. Support both the current
  three-button menu and the older two-button Options/Exit layout. With fresh
  authorized pixels and valid same-game terminal proof, permit one Escape
  dismissal attempt, then require a new authorized capture before any result
  Continue action. Never click Concede, Options, or Exit as recovery controls.

- A callback that first sees `FINAL_GAMEOVER` without a terminal `PLAYSTATE`
  starts the bounded result wait. Every later `GAME_OVER` callback must reread
  the current Power.log terminal marker; never return early solely because the
  wait has started. A later current-player `WON`, `LOST`, or `CONCEDED` releases
  the wait immediately. Regress the initial-UNKNOWN then final-WON callback
  sequence and retain the timeout case.
- A complete current-game Power.log tail may reconcile Beta from stale
  `FILL_DECK` only when it contains current-session `PLAYSTATE`,
  `FINAL_GAMEOVER`, and `STATE=COMPLETE`, local ownership is verified, and no
  active war remains. This is one bounded result cleanup attempt. Unknown OCR
  or screenshot I/O failure cannot erase that proof. A fresh positive
  `MATCHMAKING` screen still blocks duplicate queue input; terminal proof
  supersedes only uncertain stale search evidence. Clear previous queue
  evidence at the terminal boundary, and allow a new queue intent to replace
  the prior terminal input fence.
- Canonical Beta launch selects a non-runtime fixed volume with at least 5 GiB
  free for the main Logback file, launcher selection log, and guardian state.
  The launcher passes the selected paths into the JVM and refuses to start if
  no such volume exists. JVM temp and diagnostic artifacts also stay on a
  non-runtime volume. If artifact reserve is unavailable, screenshot and OCR
  image saves fail closed with a reason; they never fall back to C:. Existing
  C: log and screenshot history is retained. Test the selected paths and the
  low-space refusal with fixed-volume fixtures.
- Artifact-producing tests use `BetaArtifactStorage.usableSpaceOverrideForTest`
  to provide deterministic reserve evidence while still writing and checking
  real PNGs in isolated temporary directories. The override defaults to null,
  so production always checks the real FileStore. Restore it after each test;
  never lower the production 5 GiB threshold or rely on incidental free space
  in `java.io.tmpdir`.

## Gotchas

- After authoritative same-game terminal proof, a fresh exact-window capture may use the calibrated lower-center Continue band before OCR. This lets Victory variants with a visible Continue control remain `UNKNOWN` without waiting on slow full-screen OCR; it permits only the bounded result sequence and never infers win/loss/rank. Revalidate that the authorized frame is no older than 25 seconds at dispatch, matching the bounded observer stall budget.
- Missing process state can make `WarEx.inWar` and phase latches stale. Clear those fences only when the game process is absent and the mandatory surrender is no longer pending with terminal cleanup proof retained; never clear a pending surrender or infer terminal proof from an empty/unbound Power.log.
- When diagnosing latency, separate OCR provider duration from time until an input or state transition. Health polling at 6–38 seconds is not by itself user-visible delay; repeated `terminal-result-cleanup-pending` blocks before the coordinator starts are user-visible. Record first block, coordinator start, and confirmed destination as separate timestamps.
- Beta stale-client observations may reuse OCR text only when a newly authorized frame has an exact SHA-256 match across every pixel and dimensions, and the Hearthstone PID and HWND are unchanged. The fresh capture remains the authority for any decision; the cached OCR string is interpretation only. Any pixel, process, or window change evicts the one-entry cache and runs OCR again, so reconnect/modal overlays are still checked before a menu classification. Never use the visual sample hash or an elapsed-time cache as a substitute for exact frame equality.
- The 2026-10-27 Beta log measured stale `DECK_SELECTION` OCR at 12.7–19.0 seconds (`ocrTotalMs`; 14.4–21.0 seconds total probe) while the game was outside a match and the screen remained unchanged. `SCREEN_RECOVERY_OCR_ROI_TIMING` now reports the individual OCR region timings for future diagnosis. Repeated exact-frame probes should report `ocrPath=BETA_EXACT_FRESH_FRAME_REUSE` and spend only the fresh-frame fingerprint cost; new frames must continue through the full targeted modal/menu OCR set.
- In the same incident, `FINAL_GAMEOVER` was logged at 08:47:05.804, cleanup started at 08:47:10.274, and the first bounded Continue dispatch occurred at 08:47:16.594. The 10.79-second total includes 4.47 seconds before cleanup starts and the planned 5.76-second initial dispatch wait. Later Continue dispatches were 5.66 and 6.92 seconds apart; the minimum 5-second post-click observation interval is intentional safety pacing and remains covered by `TerminalResultSequenceControllerTest`. Do not reduce it without equivalent fresh-frame post-click coverage.
- A startup action can be the first dispatch attempt after an accepted result even when the named missing-game recovery action was never queued. Before any `startup.*` dispatch, release the stale terminal-cleanup fence only when the Hearthstone process is absent, terminal-cleanup proof was already accepted for that game, and no mandatory surrender is pending. Keep pause/work-window checks and ordinary startup input authorization after reconciliation. Regress both pending-surrender and still-live-process cases.
- Beta v4.16.679 report (Power.log session `Hearthstone_2026_10_24_19_05_28`): gen2 first cleanup block 19:13:23.161 to coordinator start 19:14:55.304 = 92.1s, destination 19:15:44.301 = 141.1s total; gen4 19:22:26.218 to 19:23:10.655 = 44.4s, destination 19:23:56.856 = 90.6s total; gen6 19:32:41.274 to 19:33:56.817 = 75.5s, destination 19:34:37.148 = 115.9s total; gen10 19:50:30.810 to 19:51:54.283 = 83.5s, destination 19:52:33.751 = 122.9s total. These intervals identify the pre-coordinator cleanup gate as the primary user-visible wait; coordinator-to-destination still takes 39.5–49.0s. The gen10 source log and saved screenshot were subsequently inspected below; the other supplied intervals remain incident evidence.
- The saved Beta gen10 trace was subsequently inspected at `Hearthstone Script Beta/log/hs_script.log`. It records late same-session terminal reconciliation at 19:50:25.262 (`gameIdentity=5:laz#12793`, `ownPlayState=LOST`, `ownConceded=true`, `capabilityIssued=true`), followed by a visible result-page Continue loop issuing generic `recovery.left` clicks that `ActionDispatchGate` rejected every ~1.08s. Hearthstone PID 83656 remained alive. The authorized capture at 19:51:42.511 showed `resultContinueGrayLight=0.040`, `resultBannerLowSaturation=0.492`, `loadingCentralDark=0.277`, `resultBannerWarm=0.402`; the screenshot is the defeat page with “Click to continue”. The wrong cause was waiting for stale-state recovery to later classify this already-proven result page. The missing-client startup-fence optimization cannot affect a live PID.
- Beta-only prevention: when late Power.log reconciliation accepts complete terminal proof, inspect the exact live PID/HWND and current same-game log identity immediately. Promote to the bounded `terminal-result.dismiss` worker only when a fresh authorized capture confirms the result page and Continue band, cleanup capability is valid, work is enabled, and no mandatory rank-surrender fence remains pending. Unknown/stale captures, absent process/window, identity mismatch, pending surrender, and all ordinary gameplay/queue actions stay denied. The bounded worker performs its own fresh post-input checks. Regression coverage includes the exact four-region gen10 screenshot signature and an allow/deny matrix for session proof, capture freshness, rank fences, channel, and action name.

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
- Game-process liveness and a stale mode/phase are not evidence of progress.
  After launch, the current-session `Power.log` can remain empty; in that case,
  ordinary recovery must not foreground or click based on stale state. Keep a
  fresh, exact-window-authorized screenshot classification path available for
  a non-game state stalled for 60 seconds, including `Mode.NONE` during
  startup, and allow input only for a recognized safe
  screen with current state confirmation. `UNKNOWN` probe retries must end in
  bounded recovery, never an indefinite defer loop. A client restart is
  permitted only after two fresh authorized observations at least 60 seconds
  apart show the same UNKNOWN screen on the same PID/HWND with perceptual
  distance at most two bits, the current-session Power.log is absent, and
  independent evidence confirms no active matchmaking. Changed identity or
  screenshot resets the timer; unavailable queue evidence, a current session,
  active game, terminal state, or recognized matchmaking defers the restart.
  The action is a no-input, no-pause client restart. Preserve active
  matchmaking/game state and manual F2 pause semantics. Lock these rules
  with deterministic tests for the stale-probe authority and bounded UNKNOWN
  policy before changing lifecycle/reconnect recovery. For the generic offline
  dialog, click only the recognized Reconnect control; never click Cancel while
  a recoverable match may still be connected, and escalate a stalled spinner
  with an automatic no-pause client restart after the bounded retry budget.
- The launcher owns a single-instance guardian outside the JVM because no
  in-process watchdog can recover after Java exits. The app writes a fresh
  `log/guardian-state.json` heartbeat with its run intent; explicit shutdown
  writes `STOPPED`, manual F2 pause writes `PAUSED_MANUAL`, and only a fresh
  `RUNNING` heartbeat can authorize a bounded script relaunch. The guardian
  never terminates Hearthstone: when its process is present it restarts only
  the script, and otherwise normal startup owns launching Hearthstone. Keep
  cooldown, per-hour restart budget, runtime mutex, launcher logs, and tests
  for explicit stop, manual pause, active-game preservation, and duplicates.
  Intentional JVM exits outside the normal shutdown path (including unsupported
  AOT/native startup) must record `STOPPED` with a reason before exiting.
  This launcher supervisor survives Java termination but not its own forced
  termination, user sign-out, or machine restart; those cases require an
  external service or Scheduled Task, which is intentionally not installed by
  this change.
- A direct manual Start click after Test Run expiry must call the configured
  bounded startup-window path again and log the decision/result. The expired
  lease itself grants no time. Active game, terminal, and recent queue evidence
  must be recorded as resume-existing-session state so Start does not discard
  or restart the live client. A closed schedule and closed startup window must
  produce a visible explanation without dispatching Start.
- A visible HUB is not, by itself, a completed startup handshake for closing
  Battle.net. Keep the platform alive through HUB and hand off only after a
  later confirmed startup phase (or authoritative in-game state); startup tests
  must retain the no-existing-Hearthstone-process launch path.
- Before the current session `Power.log` is ready, terminal and mulligan flags
  retained by the previous `WarEx` model are not authoritative blockers for a
  read-only screenshot probe. The independently observed active-game fence
  remains enforced.
- The process-wide action dispatch gate must stay closed to Login, HUB,
  Tournament, mulligan, strategy, recovery, and random/test mouse input until
  either the exact current-process `Power.log` is ready or a fresh authorized
  startup capture positively confirms a clean online menu for the same PID and
  HWND. Do not infer readiness from process liveness, mode selection, an empty
  log, or an underlying menu visible behind a modal. A current `WarEx.inWar`
  state requires current-session readiness before gameplay input, so the new
  match can start from Tournament but must reach the fresh active-game rank
  preflight before mulligan/gameplay input. Offline startup remains blocked
  except for one short-lived permit bound to the current PID, HWND, and exact
  Reconnect button point; issue it only after a second fresh, foreground- and
  pixel-authorized capture positively identifies `RECONNECT` and no active
  game or matchmaking evidence. The Yogg `SERVER_UNAVAILABLE` page never grants
  that permit. Unknown or reconnect-failure dialogs keep ordinary input
  blocked and use bounded no-pause restart/visible-alert policy. Add dispatch
  regressions for Login/HUB/Tournament/mulligan before readiness and recovery
  pass-through after a fresh online menu/session; include the captured
  disconnect attachment and the Yogg fixture to prevent classifier conflation.
- A manual Start request while already working must explicitly rearm a stale
  startup/recovery chain when the current game session is not ready. If Start
  changes the pause state, let the pause listener perform the single scheduled
  start dispatch; do not race it with a second `Core.start`. An expired Test
  Run lease is not authority to start outside both the schedule and the
  configured startup window.
- A complete, current local terminal result (`CONCEDED`, `WON`, or `LOST` with
  the final game-over marker and complete log) may release a stale rank or
  mulligan barrier for terminal-screen dismissal only. It must not grant
  ordinary gameplay input; keep the action-dispatch fence active until cleanup
  finishes. Incomplete or contradictory terminal evidence stays blocked.
- For the upstream comparison used during this recovery, the exact public
  `main` ref was `644a0a7c2ed417684d8e33c132c8c11733bc35f2`. Its reconnect
  retry primitive remains useful, but its idle-time direct restart is not
  sufficient evidence for this stale-session case. Keep bounded screen
  classification and current-session/game fences around any restart policy.
- Automation-originated failure, watchdog, reconnect, mode-confirmation,
  missing-log, missing-deck, and startup paths must not synthesize a pause.
  They may stop an unsafe stage, retry within a bound, or show an operator
  notice while preserving the user's pause state. Pause transitions remain
  manual controls; shutdown-only pause-state cleanup is not a running recovery
  action. Keep regression coverage over every production pause call site.
- The Hearthstone page reading `Yogg has devoured the server again. Please
  check back later!` is `SERVER_UNAVAILABLE`: classify it as non-game startup
  state and send no input. The 2026-10-25 incident screenshot showed legible
  parchment text while the shared Chinese OCR path returned unrelated glyphs.
  Read the fixed parchment message ROI with `eng.traineddata`, behind exact
  Hearthstone process/HWND/foreground/pixel ownership. A fresh startup probe
  classifies `SERVER_UNAVAILABLE` without input. Other screens remain
  classification-only unless the exact fresh startup rules above prove a clean
  online menu or a separately authorized reconnect modal. A restart requires repeated exact Yogg observations for the same
  PID/HWND at least 60 seconds apart, no current-session Power.log, no active
  game or queue evidence, and remaining restart budget. Restart through the
  no-pause watchdog path, enforce the five-minute cooldown and three-restart
  cap, then alert while keeping monitoring active.
- Mandatory rank surrender has its own fresh settings-overlay probe schedule:
  after the dispatched gear click, inspect at five seconds and recheck uncertain
  frames every two seconds within the bounded probe window. The generic
  thirty-second stale-screen threshold must not delay this confirmation.
  Preserve the captured settings-menu fixture and assert the scheduled probe
  actually classifies the overlay before allowing the surrender click.
- Keep rank-progress continuation signatures tied to saved client frames and
  all four measured regions (Continue band, desaturated banner, center darkness,
  and warm-color ratio). A changed rank or board palette must add an exact
  screenshot fixture with both recovery classifiers tested; UNKNOWN result
  frames retain bounded observation/retry behavior and never count an accepted
  OS input as proof of transition.
- The v4.16.698 Diamond 10 capture demonstrates that foreground/SendInput
  acceptance does not prove client consumption: the exact center Continue
  target `(960, 1020)` was inside the visible lower-center label, yet the page
  remained open. Keep the first defeat-page Continue and subsequent rank-page
  retries distinct. Replay the captured 1920x1080 rank-10 frame through both
  screen classifiers and the real bounded sequence controller; it must select
  `RANK_PROGRESS`, permit only a fresh authorized retry, and stop only after a
  positively observed destination. Never spend retries from one stale frame or
  treat dispatch acceptance as a transition.
- If the Hearthstone process disappears after complete terminal Power.log
  evidence, both the recent matchmaking-intent latch and the derived stale
  terminal-state latch may be released for the bounded configured starter
  chain. A recently observed active matchmaking screen remains an unconditional
  blocker; only stale matchmaking input intent may be superseded by complete
  proof. Release the terminal-result action fence only at the same missing
  process + complete proof boundary, then recheck guardian identity/heartbeat,
  pause, recovery generation, process absence, and action dispatch at the actual
  callback. Test the policy result and invoke the configured starter callback
  with both stale terminal latches set, plus active-screen and incomplete-proof
  negative cases. Preserve the user's working schedule intent while reconciling
  the stale GAMEPLAY mode to STARTUP.
- Recent matchmaking input intent may fence missing-client restart while a
  launch or queue is still in flight. A complete terminal proof from the
  current CREATE_GAME segment may release that stale queue-intent fence after
  the Hearthstone process disappears. Keep active-game, terminal-state,
  current-session, launcher guardian, generation, and action-dispatch checks at
  the actual starter-chain dispatch boundary, and test both the policy decision
  and invocation of the configured recovery callback.
- Startup captures must OCR the exact reconnect title/status/message bands from
  their current client coordinates. The 2026-10-26 reconnect prompt moved its
  heading to y=.21-.27 and its status/body to y=.51-.68; the previous ROIs
  started below the heading and missed most of the message, so a blank-session
  reconnect remained UNKNOWN and the existing reconnect permit path never ran.
  If localized OCR remains garbled, require the combined fixed frame signature
  (dark centered panel, parchment title tab, warm Wi-Fi emblem, and both warm
  bottom buttons); the signature classifies the reconnect dialog but does not
  itself permit input. Keep the live frame at
  `src/test/resources/fixtures/screen-recovery/offline-reconnect-modal-startup-20261026-105925.png`
  and assert both the structural signature and upstream classifier return
  RECONNECT from the actual image. The overlaid Amazon sunset frame must fail
  the reconnect signature.
  The startup reconnect action still requires a second fresh, exact PID/HWND,
  foreground/pixel-authorized capture and its one-use permit; active game and
  active or recent matchmaking evidence block it.
- Startup modal recovery is not a generic permission to click through UNKNOWN.
  The 2026-10-26 Amazon Appstore sunset notice can be recognized by its dedicated
  title (`亚马逊应用商店支持调整`) and Close (`关闭`) ROIs, or by the exact
  OCR-independent card/logo/two-button visual signature tested against the
  runtime capture `amazon-appstore-sunset-notice-runtime-20261026-123653.png`.
  Visual recognition only permits the known Close-button coordinate after a
  second fresh exact PID/HWND capture; it does not authorize broad clicking.
  Do not use `currentSessionReady` alone to deny this permit: an attached
  non-empty Power.log can coexist with the startup modal. Require the exact
  fresh notice classifier, trusted window, no active game/queue, non-gameplay
  mode, and non-terminal state at both authorization and dispatch. The central
  gate should emit `startup.notice-dismiss`; ordinary `mouse.left` and
  `mouse.right` attempts remain blocked until menu proof exists. Diagnose
  `STARTUP_NOTICE_DISMISS_BLOCKED stage=...` before treating generic mouse gate
  logs as evidence the notice path ran.
  If neither signature matches, keep the frame UNKNOWN and send no click. A stable UNKNOWN lasting at least 60 seconds may escalate to
  the established client restart only after a fresh exact-window probe again
  reports UNKNOWN, with no active game, terminal state, or recent/visible queue
  evidence; retries are bounded and back off for 120 seconds, without pausing.
  Coalesce delayed restart callbacks and defer exhaustion alerts until the
  in-flight request finishes, so repeated observers cannot queue past the cap.
  Reconfirm a clean menu after any exact-notice click before granting normal
  startup input. Keep the screenshot at
  `src/test/resources/fixtures/screen-recovery/amazon-appstore-sunset-notice-20261026-104313.png`.
  When the notice postcheck sees the HUB mode chooser (`TOURNAMENT` visual and
  Traditional Battle menu text while script mode is `HUB`), preserve a
  process/HWND-bound `HUB_MODE_CHOOSER` proof. Do not record generic online-menu
  readiness for that screen: `TournamentModeStrategy` requires the narrow
  chooser proof to dispatch its one exact entry action. A later exact,
  foreground-owned `IDLE_CLIENT_PROBE` may refresh that same short-lived proof
  after the chooser classifier passes; generic idle screenshots and menu OCR
  alone remain insufficient. The offline regression constructs the chooser
  frame over search-like colors and verifies the proof route, dispatch gate,
  and queue exclusion. When the current process has an empty or unbound
  Power.log, `WarEx.inWar` is stale phase state and must not prevent the
  bounded startup probe or chooser proof; active-game fencing requires an
  authoritative current-session model. At dispatch, the process-bound chooser
  proof may bypass stale phase flags for `mode.select-traditional` only; keep
  generic clicks, matchmaking, and gameplay input blocked.
  Regression case: after the Amazon notice closes, the mode chooser can be
  classified as `TOURNAMENT` while the script is still in `HUB`. A generic
  `STARTUP_READINESS_RECONFIRMED` proof is not sufficient for
  `TournamentModeStrategy`; the postcheck must log
  `HUB_MODE_CHOOSER_PROOF_RECONFIRMED`. If the first proof expires, a fresh
  exact `IDLE_CLIENT_PROBE` may renew only the chooser action. The symptom
  `STARTUP_MODE_ENTRY_WAITING_FOR_PROOF ... mode=HUB` after a chooser observation
  means the proof was either recorded as the wrong kind or never minted; do not
  loosen the ordinary `mouse.left` gate.
  UNKNOWN/modal captures stay input-free; preserve rank and terminal gates, do
  not block matchmaking on rank, and never synthesize a pause.
- An exact RECONNECT screen is classified from the fresh foreground capture,
  then passed through the regular screen-recovery route. The narrow
  `MATCHMAKING_ERROR_DIALOG_PROBE` may correctly return UNKNOWN for this
  non-matchmaking offline prompt; that result must not prevent the separate
  reconnect route from seeing the screen. After the bounded 60-second
  indefinite-reconnect policy fires, shut down/restart the client with
  `Core.restartFromIdleWatchdog()` without clicking Reconnect or pausing.
  Active game evidence requires both the `WarEx.inWar` latch and a non-empty
  Power.log proven to belong to the current Hearthstone process; an empty or
  stale log cannot make a stale latch authoritative. A fresh active-queue
  screen and terminal evidence also defer restart. The 15-minute matchmaking
  input intent is capture context, not proof the queue is still active. Keep
  the original reconnect age while a fresh queue briefly defers restart, so
  expired queue evidence cannot restart the 60-second grace period. The policy
  caps retries; a transient reconnect must not restart the client.
  Reconnect routing depends on `BETA_RECOVERY_EXTENSIONS_ENABLED`: disabled
  routes `ScreenStateRecovery.inspectAndRecover` to
  `UpstreamScreenStateRecovery`; enabled uses the Beta `ScreenStateRecovery`
  implementation. Keep both paths aligned: exact RECONNECT is classification
  only during a 60-second grace period, then restart Hearthstone without
  clicking Reconnect or Cancel. Require current-session authority for an
  active-game fence; a fresh queue screen and current terminal proof still
  defer restart. Reuse the persisted reconnect screenshot fixture and verify
  stale queue intent does not fence restart, while fresh queue/current game/
  terminal evidence does. `Core.restartFromIdleWatchdog()` kills/restarts the
  client without changing the script's pause state. The separate generic
  loading-screen threshold remains 120 seconds.
- Missing-client recovery must treat matchmaking/game latches as live-client
  evidence only while the exact Hearthstone process is present. If the process
  is absent, preserve the working/unpaused and guardian identity checks, wait
  the bounded startup grace, then use the configured starter chain even when
  recent matchmaking telemetry is stale. Never terminate or cancel a live
  queued client to recover a missing process.
- Terminal result cleanup has a 60-second progression target. Once an
  authorized frame shows the calibrated lower-center Continue band, classify
  defeat/rank-progress signatures from that frame and skip full-page OCR for
  the input retry. Keep the observer cadence above the bounded probe-budget
  floor. Frames without the band still require OCR to confirm a destination;
  matchmaking resumes only after fresh authorized DECK_SELECTION confirmation
  and its normal rank preflight.
- When a current foreground/PID capture confirms DECK_SELECTION, reconcile the
  same game's terminal cleanup capability before mode, deck, or queue actions.
  Unknown mode-title OCR does not confirm the requested mode and must block
  deck selection without pausing; after three unknown title observations it
  emits a bounded operator notice and returns without dispatching input. A
  positive mode mismatch also remains blocked with an operator notice. Notices
  are deduplicated by expected mode until a valid mode is observed; neither
  condition changes the user's pause state. A fresh deck-selection title and enabled Start control authorize
  only `matchmaking.start` after the observed mode matches the selected run
  mode. Do not carry the previous game's cleanup fence into the next queue.
  The selected-mode permit by itself cannot start a queue; the final click path
  must revalidate a fresh, same-window enabled deck Start proof. When title OCR
  is UNKNOWN, that proof may use a recent same-window selected-mode permit as
  secondary mode identity evidence.
  The startup mode-switch sequence uses generic mouse input after leaving the
  HUB chooser; the final dispatch gate can reject those clicks until a narrow
  mode-selector proof exists. Do not let upstream recovery call `startMatching`
  merely because the structural deck-selection classifier succeeded: it must
  first mint the fresh deck Start proof, and the production click path must
  recheck it immediately before input. UNKNOWN title OCR can use an independently
  selected same-window mode; a known opposite mode revokes that permit. We do not currently have an
  authorized capture of the tournament mode selector after opening it. A
  Standard deck screenshot may identify the mismatch, but cannot authorize
  selector or Wild-option coordinates; require a fresh selector screenshot and
  exact Wild-control classifier before either click. The supplied 1919x1079
  Standard deck frame matches the existing title template and is now accepted
  despite its missing final row/column. At the 1920x1080 client geometry,
  `CHANGE_MODE_RECT` maps to the format crest above the ranked portrait, not
  the rank badge. The supplied screenshot does not show the selector after
  opening or its Wild option, so automatic correction remains blocked pending
  selector evidence. Resume only
  after a new exact deck-selection capture confirms the configured mode. The visible tooltip
  `狂野模式套牌无法用于标准模式` with an exact deck-selection Start signature is
  a specific wrong-mode terminal state for the matchmaking-error probe: emit
  `OPERATOR_ACTION_REQUIRED`, send no confirm/Start input, and stop retry timers.
  Keep one error-dialog recovery task active so PaddleX latency cannot create a
  swarm of overlapping OCR probes.
- A victory page can leave all OCR/screen classifiers UNKNOWN even while the
  exact current game is complete and the authorized frame visibly contains
  Hearthstone's lower-center Continue band. Keep a distinct Continue-band
  observation; permit a bounded result-sequence input only with current
  capture authority and valid same-game terminal capability. UNKNOWN pixels
  without that band remain input-free. Include `continueBand` in each sequence
  observation so UNKNOWN-plus-control evidence is distinguishable from
  UNKNOWN-without-control in the live trace. If the Hearthstone process exits,
  the final startup dispatch gate must itself release `terminalCleanupPending`
  only when complete current-game Power.log proof or the already-minted
  same-game terminal-cleanup capability exists. Live process-owned game and
  matchmaking evidence remains a blocker. Complete proof may also override a
  stale `REPLACE_CARD` phase, but never an active game or queue. Preserve fresh
  guardian, generation, pause, and starter-chain checks, plus bounded retry/cooldown
  policy. Regress this exact `WON`/`LOST`/`FINAL_GAMEOVER`/`COMPLETE` tail,
  UNKNOWN-without-band rejection, UNKNOWN-with-band bounded input, and the
  gate's missing-process cleanup reconciliation with live-game/queue negative
  cases. The 2026-10-26 v4.16.703 victory screenshot was not available in the
  offline fixture tree; add it before claiming screenshot-level replay.
- Regression for the Oct 26 RC v705 incidents: an already accepted terminal-
  cleanup capability remains durable proof if the game process exits and the
  old Power.log can no longer bind as the current session. With no game process,
  ignore process-owned `WarEx.inWar` and recent-screen matchmaking latches for
  stale cleanup reconciliation; a still-live process/queue and a pending rank
  surrender remain hard blockers. Keep fresh guardian identity/heartbeat,
  running schedule, pause, recovery generation, starter-chain, and cooldown
  checks before restart. The paired Victory case measured
  continue=.040, low-saturation=.372, center-dark=.363, warm=.500: it is not a
  known defeat or rank visual signature, but the lower-center Continue band
  must yield UNKNOWN+continueControl and skip the slow OCR request. Dispatch
  remains bounded and requires the same-game terminal capability plus a fresh
  exact foreground/PID capture. Offline incident evidence and those four
  screenshot measurements are in
  `src/test/resources/offline-ocr/result-flow/rc-v705-terminal-recovery/incident-evidence.txt`;
  the original Victory PNG was not present in the integration checkout, so this
  is a measurement-level regression, not a pixel screenshot replay.
- Tess4J writes intermediate TIFF images through the JVM's `java.io.tmpdir`.
  Keep Beta's canonical launcher passing a private `-Djava.io.tmpdir` on a
  fixed volume with at least 1 GiB free, selected before the JVM starts; record
  that path in `launcher-selection.log`. This policy must be conditional on the
  Beta manifest so RC and Stable retain their existing temp configuration. Do
  not treat OCR `I/O error writing TIFF` as an empty classification: record
  capture/save, targeted OCR, secondary OCR, decision, and total durations even
  when OCR throws. Retain the bounded visual matchmaking detector and queue
  preservation if OCR or screenshot persistence fails. Regress Beta/RC channel
  separation and the no-capacity fail-closed case in
  `tests/BetaTempDirectoryPolicy.Tests.ps1`.
- Keep Beta's bulky diagnostic image and replay artifacts separate from its
  canonical runtime tree: the launcher selects a fixed volume with at least
  20 GiB free and uses `HearthstoneScriptArtifacts\Beta\<runtime-key>` for
  `mcts-replay`, `unknown-states`, `game-results`, and `mulligan`. The runtime
  key isolates separate Beta installs. Never migrate, delete, or overwrite the
  existing C: history as part of this routing. If no volume meets the primary
  threshold, use `<runtime>\log\beta-artifacts` only when its volume has at
  least 5 GiB free; otherwise mark artifact writing disabled for the new
  process and keep application logs and runtime data on their usual paths.
  Every routed writer must preserve a 5 GiB free-space reserve so artifact
  growth stops before exhausting the selected volume. Existing MCTS 50-game
  and unknown-state 100-images-per-category/day retention limits remain the
  only deletion behavior; game-result and mulligan writers stop at the reserve
  without deleting history. RC and Stable must receive no Beta artifact root.
### Beta guardian health evidence

- A fresh guardian heartbeat does not prove the app is progressing. Beta guardian snapshots also record the selected `hs_script.log` path, last-write UTC time, and file age; a stale/missing app log is surfaced as `ALERT_STALE_APP_LOG` without stopping or restarting the process.
- When the launcher observes its child exit and no restart is authorized, it must atomically reconcile only that exact PID/start-time/deployment marker to `EXITED`, record the exit code/time and last app-log write, and set the matching guardian state to `STOPPED` with unknown health. A stale PID marker must never claim `RUNNING` after observed process exit.
- Guardian health log lines must be formatted from the actual values and tested for literal `{0}`-style placeholders. Beta app-log checks must not change RC or Stable behavior.

### Beta post-result transition latency

- Diagnose post-result delay from the per-stage timings, not elapsed time alone. Keep the intentional result-screenshot animation wait separate from authorized capture, image save, visual classification, and each OCR ROI duration. A slow OCR pass is not evidence that the result page is still visible.
- On the Beta upstream OCR path, probe the exact deck-selection title ROI first. It may short-circuit only for a fresh authorized capture with confidence at or above `FreshPostResultDestinationPolicy.MIN_CONFIDENCE` and no centered modal. Matchmaking, rank reward, unknown screens, and modal-covered deck geometry must continue through the existing recognition and exact destination confirmation path; a Continue input never counts as confirmation.
- A settings-settle policy change is effective only after the corresponding Beta manifest-backed artifact is deployed and its runtime lineage is verified. In traces, distinguish the settle timer from subsequent foreground reacquisition and authorized capture time; do not attribute the latter to the timer.

- Beta reconnect regression (2026-10-28 22:14): the exact current capture was
  `RECONNECT confidence=94 evidence=reconnect-dialog-structural-visual`, but a
  recent prior MATCHMAKING screen kept the queue latch active and deferred the
  60-second no-pause restart even though the current Power.log was empty and
  current screen was not a queue. When an exact reconnect frame is confirmed,
  clear the prior active-queue-screen latch before evaluating restart; keep
  authoritative current-game and terminal-result fences. The long matchmaking
  input-intent window is historical only and must never independently veto
  this reconnect restart. Regress a recent queue-screen latch plus retained
  input-intent history through the 60-second restart, and retain negative cases
  for a current queue, authoritative live game, and terminal proof. No
  reconnect click or pause is part of this escalation.

- Beta startup deck-selection regression (2026-10-29): when broad OCR returns
  UNKNOWN but a fresh, exact client capture has the fixed enabled Start-control
  visual, no modal/quest/queue visual, and a configured deck, classify only the
  deck-selection screen. The Start proof still requires an independently
  confirmed configured format. For an exact Standard page with configured
  Wild, the opposite-mode screenshot may authorize only opening the visible
  upper-right mode crest. Capture the same PID/HWND again; click Wild only when
  that fresh centered selector modal visibly identifies Standard and OCR finds
  Wild inside the exact configured Wild-control rectangle, under a five-second
  PID/HWND-bound selector-control proof. Generic Wild text elsewhere on the
  screenshot is not target proof. A deck page mismatch alone must never
  authorize the Wild-option coordinate. Never
  pre-click a format option with a right-click because it can dismiss the
  selector. Recheck the mode after selection before deck selection or Start,
  and retain
  active-game, current-queue,
  terminal, exact-window, and post-mulligan surrender fences. Regression must
  use the saved live frame and assert that only the crest dispatch is reached,
  then require the fresh selector proof before the Wild dispatch; keep
  `matchmaking.start` blocked until the subsequent mode and deck proofs exist.
  Commit the one-attempt recovery budget only after the final mouse dispatcher
  accepts input. A stale screen or rejected dispatch releases the reservation
  so a later fresh probe can retry. Unknown OCR never means the deterministic
  click succeeded: fail closed unless a fresh visible mode identity is found.
  Use title-bar visual templates for Standard and Wild when title OCR fails.
  After configured-mode correction is accepted, schedule a fresh
  process/window-bound mode probe. Do not treat the click or the old
  deck-selection capture as proof that the configured format is now selected;
  only a subsequent fresh mode confirmation and deck-selection capture may
  authorize Start. The selected Wild deck's gray incompatibility tooltip is a
  fixed hover card above the deck list, not a centered blocking dialog. The
  tooltip alone cannot authorize mode correction; exact visible opposite-format
  title evidence plus independent configured-mode confirmation is required.
  Never waive the centered-panel veto for Start or another action.
- The generic idle stuck-screen ladder applies only to a fresh, exact-window,
  same-screen UNKNOWN blocker (using perceptual identity), exact reconnect dialog, or structurally
  confirmed HUB mode chooser in a confirmed non-game, non-queue state. A HUB
  chooser requires its dedicated visual signature and Mode=HUB; OCR labels such
  as PACKOPENING or HUB alone cannot qualify. Event Quest retains its dedicated
  exact-overlay recovery path.
  After 60 seconds, click the exact client origin three times, logging each
  dispatch and re-capturing after the sequence. For known screens, continuity
  uses semantic classification and mode evidence (for deck selection, expected
  and observed format); perceptual hash is only a fallback for UNKNOWN.
  Animation or glow noise cannot reset the ladder. A proven semantic state
  transition ends it. If the same blocker remains for another 60 seconds, re-capture and
  recheck every game/queue/pause/terminal/window fence immediately before the
  no-pause client restart. A confirmed deck-mode mismatch first gets one
  bounded configured-mode selector correction, then enters this ladder if the
  same opposite mode persists. Event Quest, results, gameplay, queue and pause
  remain owned by their dedicated recovery paths. Exact
  reconnect uses the staged top-left-only ladder; never click Reconnect or
  Cancel. Reconfirm current PID and Power.log/session evidence immediately
  before its final restart. Known-screen identity must tolerate
  small animation/glow changes but distinguish a real screen transition; it is
  bound to PID/HWND rather than lifecycle phase labels.
- A post-game Event Quest overlay can leave the in-memory Tournament phase at
  `FILL_DECK` after `WarEx` cleanup. Permit its exact top-left dismiss only
  when the fresh foreground-owned client capture confirms the structural Event
  Quest card and the current-session Power.log has complete terminal markers
  plus a locally verified `WON`, `LOST`, or `CONCEDED` outcome. This terminal
  exception is limited to Tournament/FILL_DECK; it cannot override active
  gameplay, a current matchmaking screen or short race guard, pause, or either
  mandatory-rank-surrender guard. After each dispatch, capture and classify
  again: accepted SendInput is not proof that the card or game menu changed.
  Restart at most once per automation run and semantic underlay; a repeated
  post-restart overlay logs an alert and stops automatic restarts. Regress the
  `FINAL_GAMEOVER` + stale `FILL_DECK` path with exact overlay, incomplete or
  unowned terminal log, false overlay, failed foreground proof, active game,
  active queue, pause, accepted click without visual transition, verified
  transition, and restart-cap exhaustion.
- After a Hearthstone restart, an Event Quest card can cover the startup menu
  while the model still says `LOGIN|FILL_DECK` and the current Power.log is
  empty. This is a narrow recovery exception only: bind the readable zero-byte
  Power.log to the current Hearthstone process start, require an exact fresh
  foreground PID/HWND capture with the structural Event Quest card, and retain
  all no-session-ready, no-game, no-queue-screen/intent/race, no-reconnect,
  no-rank-guard, working, and unpaused fences. Empty bytes are not session
  progress or gameplay authority. Recheck that proof on every top-left retry
  and before the bounded no-pause restart. If a later fresh capture shows the
  quest card gone and no centered modal, the same empty-session/window proof
  may release the recovery fence without sending another input; overlay
  absence alone, reconnect/queue frames, and untrusted captures cannot release
  it. Regress the positive startup path, each fence, zero-byte process lineage,
  unchanged overlay through restart-cap alert, and verified overlay-absent
  fence release.
- For an exact reconnect screen, the 60-second no-pause restart grace must
  schedule its own fresh authorized capture at the threshold rather than wait
  for the next slow periodic OCR pass. Re-evaluate current active-game,
  matchmaking-screen/short-race, and terminal fences on that capture and again
  before restarting. Cover the timer cadence deterministically; OCR latency or
  a delayed global probe must not silently extend the configured grace.
- Once a fresh exact HUB mode chooser is confirmed after a deck-mode Return,
  reset that deck-screen's one-attempt navigation budget. This permits one new
  bounded Return only after the app re-enters a fresh wrong-mode deck screen;
  an unchanged deck frame cannot create repeated Back clicks. Regression should
  replay the live Standard chooser with its configured Wild deck tooltip,
  assert Return reaches the dispatcher, confirm the fresh selector stage, and
  keep Start denied until fresh WILD mode and deck proof exist.
- A fresh exact HUB chooser capture may authorize only one Traditional Battle
  entry action before a current Power.log exists, provided exact PID/HWND,
  foreground/pixel authority, no-game/no-queue/no-terminal fences, and
  unpaused/working checks pass. Consume chooser proof on accepted dispatch;
  UNKNOWN post-click captures cannot mint or reuse it. A retry requires a new
  exact HUB capture. HUB proof never authorizes deck Start. Test after
  Hearthstone restart with an empty new Power.log and a captured HUB fixture.
