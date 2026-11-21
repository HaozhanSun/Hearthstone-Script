# Beta candidate-screen watchdog closeout

Date: 2026-11-20
Branch: `beta/fix-debug-runner-diagnostic-root-20261115`
Scope: source-side Beta recovery change only; no deployment, process restart, or live Hearthstone interaction was performed.

## Problem and known-good comparison

The existing Beta recovery path was not a universal screen-stability monitor. The
optional `NoProgressWatchdog` is gated by usable `Power.log` evidence and defaults
to 180 seconds before its existing rebind/restart policy. The older idle guard is
also bounded by its configured idle window and the RC unknown-screen stability
guard. Those paths remain unchanged.

The existing serialized client-restart contract in `Core.restartFromIdleWatchdog`
force-closes the current client and then dispatches the canonical starter chain.
The new candidate monitor uses that contract only for its newly added candidate
screen episode. The established Power.log no-progress branch still uses
`NoProgressRecoveryDispatch.restartToStartup`.

## Implemented behavior

- Beta-derived builds schedule a read-only fresh-capture candidate monitor every
  10 seconds.
- A recognized non-healthy screen or classified dialog must remain positively
  identified for 120 seconds, across at least two fresh foreground captures,
  with the same PID, HWND, and foreground identity.
- A centered modal panel whose OCR is unknown can qualify only through the
  existing visual modal signature plus a centered-modal ROI fingerprint. Plain
  `UNKNOWN`, failed capture, low-confidence OCR, or an unstable modal fingerprint
  never qualifies.
- Healthy screens, active games, mulligan, terminal/result screens, manual pause,
  and a changed client/window reset or defer the episode.
- Candidate restarts recheck the live runtime fence immediately before calling
  `Core.restartFromIdleWatchdog`, then use a bounded repeat backoff and restart
  budget for the same identity.
- Existing exact start-game-error modal precedence is reused through a production
  classifier seam; the deck-selection underlay cannot hide the modal.
- The checked-in Beta release test selector and its selector regression test now
  include both new watchdog tests.

## Verification

Focused Maven reactor test command:

```text
./mvnw.cmd -pl hs-script-app -am -Dbuild-channel=beta -Dkotlin.compiler.jvmTarget=24 -Dtest=BetaCandidateScreenWatchdogTest,BetaCandidateScreenRecoveryIntegrationTest,NoProgressWatchdogTest,IdleWatchdogRestartLaunchIntegrationTest,MatchmakingSearchRecoveryTest,MatchmakingDialogRecoveryPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: **67 tests passed; 0 failures; 0 errors; BUILD SUCCESS.** This includes:

- exact 120,000 ms threshold, identity changes, safety guards, backoff, and
  exhaustion;
- OCR-unknown rejection and centered-modal visual-only acceptance;
- centered-modal ROI stability across changed underlays and changed modal content;
- the provided `start-game-error-dialog-over-deck-selection.png` fixture reaching
  `START_GAME_ERROR_DIALOG` and `RESTART` at exactly 120 seconds;
- serialized force-close/canonical-starter ordering; and
- existing no-progress and matchmaking/modal regressions.

The release selector contract also passed:

```text
pwsh -NoProfile -NonInteractive -File tests/BuildAndDeployVersion.Tests.ps1
```

Result: **PASS.**

## Prevention and handoff

The policy tests, integration source-order test, image fixture tests, and release
selector assertion are the prevention contract. The source commit is ready for
secretary review/integration. Deployment and live Beta restart remain pending by
design and must follow the repository Beta deployment SOP in the secretary
worktree.
