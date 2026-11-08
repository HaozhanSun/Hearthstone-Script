# Problem-resolution closeout

Issue: 2026-11-07 · #2 Beta release-channel isolation Pester regression
Date: 2026-11-07
Status: OPEN — offline source/test correction is complete; deployment and the shared online E2E completion gate remain owned by the secretary.

Investigation and cause: PASS — `tests/ReleaseChannelIsolation.Tests.ps1` failed on the current Beta branch before deployment because it required the checked-out source to identify as the obsolete `beta/release-candidate-v4.16.551` RC branch. The current source is intentionally Beta (`beta/wild-mode-selector-recovery-20261101`); the test therefore conflated a Beta checkout with the RC fixture it was meant to validate.

Fix: PASS — the test now first verifies the checked-out source as a non-RC Beta channel using the shared `verify-release-channel.ps1`. It separately creates a disposable synthetic RC metadata fixture (`beta/release-candidate-contract-test`) and executes the same verifier against that stable RC branch shape. Existing assertions continue to exercise the shared deployment-contract manifest resolver, reject cross-channel manifests, and verify RC-only icon/shortcut contract text. No canonical runtime, RC runtime, manifest, launcher, or shortcut was changed.

Verification: PARTIAL — `pwsh -NoProfile -File tests/ReleaseChannelIsolation.Tests.ps1` passed after the change. The complete required offline Pester set also passed: `DiagnosticRetentionScheduler.Tests.ps1`, `BetaDeploymentProcessStop.Tests.ps1`, `ReleaseChannelIsolation.Tests.ps1`, and `SyncShortcutsBetaDuplicate.Tests.ps1`. This is source-only pre-deployment evidence; it does not replace deployment verification or the shared online E2E completion gate.

Future prevention: PASS — channel-isolation tests must distinguish the checked-out source channel from the synthetic channel fixture used to validate alternate release-channel behavior; do not hard-code any deployed, current, or historical release branch into fixture metadata.

Prevention enforcement: PASS — test: `ReleaseChannelIsolation.Tests.ps1` invokes the shared verifier once for current Beta metadata and once for a disposable synthetic RC metadata fixture, so an invalid Beta declaration or invalid RC verifier path fails independently without depending on an actual RC branch name.
