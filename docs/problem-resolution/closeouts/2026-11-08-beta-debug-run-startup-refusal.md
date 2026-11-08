# Problem-resolution closeout

Issue: 2026-11-08 · Beta Debug Run hard-cap refusal could be cleared at startup
Status: OPEN — Source fix and focused regression coverage passed locally;
deployment and online Beta verification remain with the secretary.

## Investigation and cause

Known-working startup behavior was inspected at `7a2a650c` and its predecessor
to the hard-cap change, `d16bdbcc^`. `START_ON_OPEN` and explicit startup signals
cleared the initial pause. Commit `d16bdbcc` introduced the Beta + Never
Surrender hard-cap refusal: it disables the Debug Run, sets a manual pause, and
records `hardCapRefusalReason`. The independent startup decisions in
`MainApplication.showMainPage()` and `checkArg()` still assigned
`PauseStatus.isPause = false` without consulting that refusal. `checkArg()` runs
after the main window is shown, so it could undo the refusal after watchdog
arming failed.

## Fix

Both startup decisions now include the recorded hard-cap refusal in
`shouldAutoStart()`. The eventual asynchronous/delayed resume also checks the
refusal again immediately before clearing pause, closing the race between the
decision and dispatch. A blocked start logs `AUTO_START_BLOCKED` with its source
and normalized refusal reason. Successful watchdog arming and channels that do
not require the Beta hard cap keep their prior `START_ON_OPEN` behavior.

## Verification

PASS — Maven reactor command with Java 24 targeted
`InstanceIdentityIsolationTest`, `DebugRunUiContractTest`, and
`DebugRunHardCapSupervisorTest`: 11 tests, 0 failures/errors. The regression
combines the real hard-cap refusal predicate with `START_ON_OPEN` and checks
failure, successful watchdog arming, and a channel where the hard cap is not
required. The UI contract test guards both production callsites and the
dispatch-time refusal recheck. `git diff --check` passed. No application or
game was launched.

## Future prevention

Keep startup auto-start decisions centralized in `shouldAutoStart()` and route
every startup resume through `resumeFromStartupAutoStart()`. Any new startup
path must preserve the hard-cap refusal gate rather than assigning
`PauseStatus.isPause = false` directly.

Prevention enforcement: `InstanceIdentityIsolationTest` covers refusal versus
successful and non-applicable cap cases; `DebugRunUiContractTest` asserts that
both startup decisions and dispatch-time resume retain the guard.
