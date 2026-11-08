# Problem-resolution closeout

Issue: 2026-11-07 · #3 Beta deploy preflight blocked by foreign Release Candidate JVM
Date: 2026-11-07
Status: OPEN — source-only safety correction and offline contracts are complete; the secretary owns deployment and the shared online E2E completion gate.

Investigation and cause: PASS — the checked-in Beta release path refused before runtime mutation because Java PID 44880 had no readable command line and no Beta ownership marker. The pre-deploy evidence identified that exact PID as the separate Release Candidate window (`HS Script Release Candidate`), launched under PowerShell PID 49008 with Battle.net child PID 46784; Beta had no `hs-script.pid.json`. The process guard correctly avoided termination but treated every unmarked, command-line-hidden Java process as unknown, so it could not distinguish this verified foreign RC instance.

Fix: PASS — `Stop-ManagedHsScriptProcesses` now performs a separate exact-PID window-title lookup only for command-line-hidden Java processes with no Beta marker. It excludes only an exact `HS Script Release Candidate` title as a foreign process, records no Beta ownership from that title, and never includes it in the stop set. Missing, changed, or unrelated titles retain the prior fail-closed refusal. The Beta marker/PID/start-time/manifest verification path is unchanged. Deployment SOP read — reviewed `AGENTS.md`, `docs/OPERATIONS-SOP.md`, `docs/RELEASE-CHANNELS.md`, and the existing process/hard-cap closeout before editing; the reusable process-identity rule is consolidated in Deployment SOP §2.1 and reconciled with the Beta channel rule in `RELEASE-CHANNELS.md`.

Verification: PARTIAL — `tests/BetaDeploymentProcessStop.Tests.ps1` passed and covers exact RC title exclusion, unknown-title refusal, preserved exact Beta marker ownership, and unrelated visible JVM preservation. The complete required Pester set passed: `DiagnosticRetentionScheduler.Tests.ps1`, `BetaDeploymentProcessStop.Tests.ps1`, `ReleaseChannelIsolation.Tests.ps1`, and `SyncShortcutsBetaDuplicate.Tests.ps1`; this closeout also passed `validate-problem-resolution-closeout.ps1`. No deployment, runtime, RC process, launcher, manifest, shortcut, or game was touched; therefore live deployment/online E2E evidence remains required.

Future prevention: PASS — a foreign-window exception must be an exact, non-ownership exclusion with a dedicated test for both the permitted foreign identity and every refused unknown identity; never broaden hidden-Java handling into a generic ignore rule.

Prevention enforcement: PASS — test: `BetaDeploymentProcessStop.Tests.ps1` fails if an exact RC title is stopped, an unknown title is accepted, an RC title suppresses an exact Beta marker process, or a visible unrelated JVM is stopped.
