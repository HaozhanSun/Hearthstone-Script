# Problem-resolution closeout

Issue: 2026-11-02 · #1 Beta GUI Debug Run process hard-cap
Date: 2026-11-02
Status: OPEN — Offline implementation and focused tests are complete; deployment and the required live Beta online gate are reserved for the secretary and were explicitly excluded from this worker scope.

Investigation and cause: PASS — `DebugRunLease` gave the GUI a 30-minute in-process deadline, while only `run-debug.ps1` armed the existing external PID/start-time hard-cap and that CLI cap was limited to 900 seconds. A GUI lease could therefore expire without a process-level terminator or an explicit guardian stop intent.

Fix: PASS — Added `DebugRunHardCapSupervisor` to arm a detached 30-minute watchdog only for Beta plus the existing Never Surrender switch, atomically write the active GUI lease ownership record, and cancel it for UI disable/restart. Added the packaged watchdog script and deployment copy rule. The hard-cap module now rejects cancelled/replaced leases, verifies PID plus process start time, writes a matching `STOPPED` guardian intent before termination, and then stops owned Hearthstone descendants followed by the JVM.

Verification: PARTIAL — `pwsh -NoProfile -File hs-script-app/src/test/resources/bat/DebugRunHardCap.Tests.ps1` passed, covering exact deadline, process ownership, process-tree order, stale PID, cancellation, re-arm/extension, restart, and guardian `DO_NOT_RESTART`. Maven focused tests `DebugRunLeaseTest`, `DebugRunHardCapSupervisorTest`, and `DebugRunUiContractTest` passed (11 tests). `NeverSurrenderPolicyTest` passed (11 tests), including active lease authorization and expiry reversion at the mulligan policy boundary. `MulliganRankPreflightTest` ran 20/21 successfully; its sole failure is an unrelated pre-existing missing retained-screenshot fixture at line 901, not a rank-policy assertion. No runtime was launched, deployed, or played under this worker scope.

Future prevention: PASS — Keep the GUI lease file as the single external ownership token so every old watchdog becomes a no-op after cancellation, re-arm, extension, or restart; require the guardian’s non-restartable intent before any hard stop.

Prevention enforcement: PASS — test: `DebugRunHardCap.Tests.ps1` rejects early/stale/cancelled/replaced termination and asserts guardian `DO_NOT_RESTART`; `DebugRunHardCapSupervisorTest` rejects non-Beta, Never Surrender disabled, and non-30-minute plans; `DebugRunUiContractTest` rejects loss of GUI wiring or packaged watchdog deployment.
