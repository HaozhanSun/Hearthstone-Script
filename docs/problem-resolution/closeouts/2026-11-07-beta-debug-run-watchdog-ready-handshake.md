# Problem-resolution closeout

Issue: 2026-11-07 · #1 Beta Debug Run watchdog READY handshake refusal
Date: 2026-11-07
Status: OPEN — source checkpoint and focused offline verification are in scope; Beta deployment and the shared online E2E completion gate remain owned by the secretary.

Investigation and cause: PASS — Beta logs recorded `DEBUG_RUN_HARD_CAP_NOT_ARMED reason=watchdog-ready-handshake-failed` followed by `DEBUG_RUN_HARD_CAP_REFUSED ... LEASE_DISABLED`. The detached watchdog writes its READY record through PowerShell `ConvertTo-Json`, which emits spaces and newlines around JSON separators. `DebugRunHardCapSupervisor.awaitWatchdogReady` previously accepted only minified literal field fragments, so it could reject an otherwise valid watchdog ownership record before the short handshake deadline elapsed.

Fix: PASS — `DebugRunHardCapSupervisor.readyRecordMatches` now accepts optional JSON whitespace while requiring the contract schema (`schema=1`), READY state, run ID, process ID, and deadline epoch. It therefore preserves ownership binding while accepting PowerShell-formatted JSON. `DebugRunHardCapSupervisorTest` rejects altered schema, state, run ID, process ID, and deadline values.

Verification: PARTIAL — the focused full-reactor Maven command specified below passed after the change; Surefire recorded `DebugRunHardCapSupervisorTest`: 4 run, 0 failures, 0 errors, 0 skipped. Full-suite parent-equivalence limitation: no full Maven suite was run for this narrow handshake checkpoint, because it would combine unrelated modules/tests and user-owned POM timestamp dirtiness rather than establish equivalence to the exact deployed parent. The claimed verification is therefore limited to the focused full-reactor test and does not satisfy deployment or online E2E evidence. No runtime was launched, stopped, or deployed.

Future prevention: PASS — treat the watchdog READY file as structural JSON rather than a minified string protocol, and keep every ownership field checked before arming a hard cap.

Prevention enforcement: PASS — test: `DebugRunHardCapSupervisorTest` passes PowerShell-formatted READY JSON and rejects mismatched schema, state, run ID, PID, and deadline; this prevents a formatting-only handshake regression while rejecting stale or foreign watchdog ownership records.
