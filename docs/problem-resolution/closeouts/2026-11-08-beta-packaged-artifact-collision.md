# Problem-resolution closeout

Issue: 2026-11-08 · #1 Beta retry must not reuse a locally packaged artifact version
Date: 2026-11-08
Status: OPEN — source-only collision protection and offline tests are complete; deployment and the shared online E2E completion gate remain owned by the secretary.

Investigation and cause: PASS — the first Beta deployment attempt packaged `v4.16.748-local-20261107-235212PST` and then safely refused before runtime mutation because a verified foreign RC process was present. The JAR and ZIP remained in `hs-script-app/target`, while all twelve POMs moved from `v4.16.747` to `v4.16.748` with that attempt timestamp. Version selection compared the newer source POM against deployed `v4.16.747`, but collision protection exposed only one ambiguously named artifact root; a retry could therefore reuse the prior local artifact/version instead of treating both the canonical runtime and packaged source output as collision domains.

Fix: PASS — `Get-BetaCollisionSafeArtifactVersion` now accepts explicit canonical-runtime and local-source-artifact roots and rejects a candidate when either contains its JAR or ZIP. `build-and-deploy.ps1` passes both the Beta runtime root and `hs-script-app/target`. Existing artifacts are preserved; collision resolution advances the patch and receives a fresh timestamp through the normal version function. Deployment SOP read — audited `AGENTS.md`, `docs/OPERATIONS-SOP.md`, `docs/RELEASE-CHANNELS.md`, and the relevant process/release closeouts before editing. `RELEASE-CHANNELS.md` contains no competing version-collision rule, so the reusable packaging requirement is consolidated in Deployment SOP §2.2 without changing channel authority.

Verification: PARTIAL — `tests/BuildAndDeployVersion.Tests.ps1` passed and covers runtime collision, pre-existing local packaged JAR/ZIP collision, preservation of both prior artifacts, and normal newer-source-versus-deployed version selection before collision resolution. This closeout passed `validate-problem-resolution-closeout.ps1`. No generated artifact was deleted and no runtime, manifest, launcher, shortcut, process, or deployment was modified; live deployment/online E2E evidence remains required.

Future prevention: PASS — every Beta retry collision guard must take named runtime and source-output roots, test each independently, and treat a failed pre-install attempt as an artifact-producing event rather than disposable output.

Prevention enforcement: PASS — test: `BuildAndDeployVersion.Tests.ps1` fails if a pre-existing local target JAR/ZIP does not force a new patch/timestamp, if a runtime collision is ignored, if prior artifacts are altered, or if the build script does not pass both roots to the collision helper.
