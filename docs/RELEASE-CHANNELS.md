# Stable, beta, and release-candidate channels

These are intentionally separate release channels with distinct runtime roots,
manifests, single-instance identities, and shortcuts.

## Stable

- GitHub `main` is the stable source of truth.
- `release-channel.json` on `main` must declare `channel: stable` and
  `branch: main`.
- Stable deploys use the canonical `Hearthstone Script` runtime and the
  `Hearthstone Script.lnk` shortcuts.
- A stable release must be buildable, pass the checked-in regression tests, and
  pass the online E2E completion gate before it is promoted to `main`.

## Beta

- Experimental work lives on `beta/*` branches. A beta branch must declare
  `channel: beta` in its own `release-channel.json`.
- Beta deploys use a separate `Hearthstone Script Beta` runtime and
  `Hearthstone Script Beta.lnk` shortcuts, so they cannot overwrite stable
  files, manifests, PIDs, logs, or launcher selection.
- Beta shortcuts use `hs-script-beta.ico`, which is generated from the stable
  application icon with a visible `B` badge. Stable shortcuts continue to use
  the application icon embedded in `hs-script.exe`.
- Beta is allowed to fail or change behavior while it is under investigation;
  it must never be promoted to `main` merely because it compiles.
- CI labels beta artifacts as beta and validates that the branch metadata and
  runtime identity are consistent.

## Release Candidate

- Release-candidate builds are prepared on `beta/release-candidate-*` branches
  from a reviewed Beta source snapshot, but use `channel: release-candidate`.
- They install only to `Hearthstone Script Release Candidate` and use
  `Hearthstone Script Release Candidate.lnk` plus
  `hs-script-release-candidate.ico` with an `RC` badge. They have their own
  manifest and launcher directory; that launcher rejects manifests whose
  declared channel/root do not match the Release Candidate runtime.
- Release Candidate keeps Beta-derived app behavior, including the isolated
  Beta Hearthstone executable selection and optional Beta recovery controls,
  while using a distinct app title, mutex, activation signal, runtime root,
  manifest, and shortcuts.
- Preparing an RC artifact does not change Beta or Stable installations. Only
  the secretary installs it, after reviewing the artifact and exact RC paths.

## Promotion and rollback

1. Start from the last known-good stable commit and record the working-version
   comparison when repairing a regression.
2. Develop and test experimental and Release Candidate work on `beta/*` in an
   isolated worktree.
3. Deploy beta only to the beta runtime and run the online E2E gate against the
   exact beta artifact.
4. Promote by merging the verified beta commit into `main`; do not copy a JAR
   over stable by hand.
5. If beta fails, leave `main` and its stable runtime unchanged. Roll back by
   selecting the previous stable commit through the normal release path.

The local `build-and-deploy.ps1` and `sync-shortcuts.ps1` scripts enforce the
runtime and shortcut separation. The deployment manifest records the channel,
runtime root, and shortcut name so stale beta artifacts cannot be selected by a
stable launcher.

## Beta to Release Candidate promotion

Use the checked-out operations/main copy of the promotion helper, but pass
`-ProjectRoot` for a clean Beta checkout at the exact source commit recorded in
the Beta manifest. The helper itself must not be run with its default project
root: that would inspect the operations checkout instead of the Beta source.
The current deployed Beta identity is branch
`beta/current-rank-candidate-v4.16-integration`, commit
`4bc5beba9ac65ad3c3b7f35777ae2f8e110fe9eb`, artifact
`hs-script_v4.16.679-local-20261023-223850PDT.jar`. These PowerShell variables
use the current canonical channel roots and the pushed operations checkout:

```powershell
$scriptRoot = 'C:\Users\yzjsh\.codex\worktrees\rc-promotion-ops-main-20261023'
$betaProjectRoot = 'C:\Users\yzjsh\.codex\worktrees\beta-source-v4.16.679-4bc5beba'
$betaRuntimeRoot = 'C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta'
$rcRuntimeRoot = 'C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Release Candidate'
$betaBranch = 'beta/current-rank-candidate-v4.16-integration'
$betaCommit = '4bc5beba9ac65ad3c3b7f35777ae2f8e110fe9eb'
```

Create the clean, exact-source checkout once. This clone gives the old
deployed commit its manifest-declared branch name without disturbing the
existing, newer Beta worktree:

```powershell
git clone --no-checkout https://github.com/HaozhanSun/Hearthstone-Script.git $betaProjectRoot
if ($LASTEXITCODE -ne 0) { throw 'Could not clone the Beta source checkout' }
git -C $betaProjectRoot fetch origin $betaBranch
if ($LASTEXITCODE -ne 0) { throw 'Could not fetch the Beta source branch' }
git -C $betaProjectRoot switch --create $betaBranch $betaCommit
if ($LASTEXITCODE -ne 0) { throw 'Could not check out the exact deployed Beta source commit' }
if ((git -C $betaProjectRoot rev-parse HEAD).Trim() -ne $betaCommit) { throw 'Beta source checkout is not at the manifest commit' }
if ((git -C $betaProjectRoot branch --show-current).Trim() -ne $betaBranch) { throw 'Beta source checkout branch does not match the manifest' }
```

Online E2E evidence is optional for creating an RC candidate; this does not
relax the online gate for Stable or authorize merging an unverified candidate
to `main`. For the current legacy RC, first run the read-only pending-E2E
preflight below. It omits `-VerificationEvidencePath` and includes the explicit
`-AllowLegacyRcUpgrade` opt-in:

```powershell
pwsh -NoProfile -File (Join-Path $scriptRoot 'promote-beta-to-release-candidate.ps1') `
  -ProjectRoot $betaProjectRoot `
  -BetaRuntimeRoot $betaRuntimeRoot `
  -ReleaseCandidateRuntimeRoot $rcRuntimeRoot `
  -AllowLegacyRcUpgrade `
  -PreflightOnly
if ($LASTEXITCODE -ne 0) { throw 'RC preflight failed; do not promote' }
```

Proceed only if preflight prints `PREFLIGHT_OK`. This is the exact pending-E2E
promotion command for the current Beta/legacy-RC pair; it deliberately has no
`-VerificationEvidencePath`:

```powershell
pwsh -NoProfile -File (Join-Path $scriptRoot 'promote-beta-to-release-candidate.ps1') `
  -ProjectRoot $betaProjectRoot `
  -BetaRuntimeRoot $betaRuntimeRoot `
  -ReleaseCandidateRuntimeRoot $rcRuntimeRoot `
  -AllowLegacyRcUpgrade
if ($LASTEXITCODE -ne 0) { throw 'RC promotion failed' }
```

For another Beta source snapshot or after this legacy RC upgrade, update the
branch, full commit, artifact identity, and exact-source clone path from the
Beta deployment manifest. If verified E2E evidence is available instead, pass
the same `-VerificationEvidencePath` JSON to both preflight and promotion; all
other roots and `-ProjectRoot` remain explicit.

When supplied, the evidence file must contain `status: PASS`,
`releaseChannel: beta`, the exact `sourceBranch`, `sourceCommit`, `artifactName`,
and `artifactSha256`, `gamesCompleted >= 3`, `terminalMarkers >= 3`,
`scriptProcessStable: true`, and an existing `finalScreenshotPath`. Supplied
but stale, malformed, or mismatched evidence remains a hard refusal; it never
silently degrades to pending. With no evidence, both `release-channel.json`
and the deployed RC manifest record `onlineE2eStatus: PENDING`, bind the status
to the exact Beta artifact name/hash, leave the evidence hash blank, and set
`stableReady: false` / `stableReadiness: NOT_READY_E2E_PENDING`. With valid
evidence, they record `onlineE2eStatus: PASS` and the evidence JSON SHA-256,
still set `stableReady: false`, and require
`stableReadiness: REQUIRES_INDEPENDENT_MAIN_GATE_REVIEW`. An RC candidate is
never automatically Stable-ready; the daytime secretary must independently
verify the full Stable gate. The script also accepts `-ShortcutRoot` for
offline contract tests; normal promotion verifies the real Desktop, Start
Menu, and Taskbar locations.

The command reads the current Beta deployment manifest and requires its
`sourceBranch` and `sourceCommit` to match the checkout exactly. A post-deploy
Beta checkout may contain only generated POM version updates to the manifest's
exact artifact version and the root `pom.xml`
`local-build-timestamp-pacific` update to the manifest's exact timestamp. This
handles the checked-in build path's version propagation across module POMs;
all other staged, unstaged, or untracked changes are rejected. It then
creates or reuses a `beta/release-candidate-*` branch in a separate worktree,
commits only Release Candidate channel metadata, and invokes that worktree's
checked-in `build-and-deploy.ps1 -Channel release-candidate`. The build path
controls the isolated Release Candidate runtime, manifest, icon, and Desktop,
Start Menu, and Taskbar shortcuts. It never copies a JAR into Beta or Stable.

The generated manifests record `sourceBranch`/`sourceCommit` for the actual
checkout and `promotionSourceBranch`/`promotionSourceCommit` for the Beta
snapshot. A rerun with a matching Release Candidate manifest exits with
`PROMOTION_ALREADY_COMPLETE` without rebuilding or touching the runtime.
Missing source metadata, a dirty checkout, a branch/channel mismatch, an
existing worktree at an unexpected path, a missing Beta manifest, invalid
supplied E2E evidence, an embedded JAR version/channel/timestamp mismatch, an
invalid manifest hash, a missing launcher/shortcut, duplicate or stale RC
shortcuts, a non-numeric Beta artifact version, or a non-empty RC runtime with
no validated matching manifest is a hard refusal. A valid older RC installation
is still a hard refusal unless `-AllowLegacyRcUpgrade` is explicit. With that
switch, the script captures an identifiable rollback snapshot of RC-managed
artifacts, launcher files, and shortcuts, leaves `config`, `data`, `log`,
custom plugin files, and backups in place, and verifies those protected files
after the build. Unknown or malformed RC state still fails closed. The
secretary owns the actual build/deploy invocation and live restart.

For repeat use, rerun the same command with the same evidence only after the
Beta manifest still names the same source commit and artifact. A matching RC
manifest returns `PROMOTION_ALREADY_COMPLETE` without rebuilding or changing
shortcuts. For rollback, do not copy a JAR by hand: select the previously
verified Beta source snapshot, rerun preflight, and use the same promotion
script so the RC manifest, artifact hashes, launcher, and three shortcuts are
validated together. Verify the resulting RC manifest and launcher before any
live restart. This runbook is operations-only and targets GitHub `main`; it must
not be merged together with Beta application or POM commits.

## PaddleX runtime parity

Both channels use the same PaddleX sidecar contract. During deployment,
`build-and-deploy.ps1` fills only blank `PADDLEX_OCR_PYTHON`,
`PADDLEX_OCR_MODULE_PATH`, and `PADDLEX_OCR_MODEL_CACHE` values in the channel
`config/script.ini`; existing non-empty user values are preserved. Runtime
resolution uses the same order when a key remains blank: configured value,
environment variable, packaged module path or the per-user PaddleX venv/cache
default. The application startup log records the resolved values and health
check result, so a missing PaddleX installation is explicit rather than a
silent legacy-provider substitution.
