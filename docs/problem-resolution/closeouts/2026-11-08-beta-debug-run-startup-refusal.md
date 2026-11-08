# Problem-resolution closeout

Issue: 2026-11-08 · Beta Debug Run hard-cap refusal could be cleared at startup
Date: 2026-11-08
Status: OPEN — source fix and offline regression tests passed; Beta deployment and live correlation remain pending with the secretary.

Investigation and cause: PASS — Compared known-working startup behavior at `7a2a650c` and `d16bdbcc^` with hard-cap introduction `d16bdbcc`. The new refusal path disables Debug Run, sets a manual pause, and records `hardCapRefusalReason`. The independent auto-start paths in `MainApplication.showMainPage()` and `checkArg()` still unconditionally cleared pause; because `checkArg()` runs after the main window appears, `START_ON_OPEN` could undo watchdog-arm refusal.

Fix: PASS — Both startup decisions now reject automatic resume while a hard-cap refusal is recorded. The asynchronous/delayed resume rechecks the refusal immediately before clearing pause. Blocked starts emit `AUTO_START_BLOCKED` with source and normalized refusal reason. Successful watchdog arm and channels that do not require the Beta cap preserve normal `START_ON_OPEN` behavior.

Verification: PARTIAL — The Java 24 Maven reactor run passed `InstanceIdentityIsolationTest`, `DebugRunUiContractTest`, and `DebugRunHardCapSupervisorTest`: 11 tests, 0 failures/errors. `git diff --check` and `git show --check` passed. Beta deployment and live correlation remain pending with the secretary; no application or game was launched in this source-only task.

Future prevention: PASS — Keep startup auto-start decisions centralized in `shouldAutoStart()` and route every startup resume through `resumeFromStartupAutoStart()`. Any new startup path must preserve the hard-cap refusal gate instead of assigning `PauseStatus.isPause = false` directly.

Prevention enforcement: PASS — test: `InstanceIdentityIsolationTest` covers watchdog-arm failure, successful arm, and channels not requiring the hard cap; `DebugRunUiContractTest` asserts both production decisions and the dispatch-time refusal recheck.
