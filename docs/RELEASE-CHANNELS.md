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

Use the checked-in promotion command from a named Beta checkout after the Beta
artifact has passed the online E2E gate. First run the read-only preflight. The
gate must be exported as a JSON evidence file bound to the exact deployed Beta
commit and JAR:

```powershell
pwsh -File .\promote-beta-to-release-candidate.ps1 `
  -VerificationEvidencePath .\evidence\beta-online-e2e.json `
  -PreflightOnly
```

For the currently installed legacy RC (a valid older RC manifest without
promotion-source fields), add the explicit reviewed upgrade switch to both
preflight and promotion:

```powershell
pwsh -File .\promote-beta-to-release-candidate.ps1 `
  -VerificationEvidencePath .\evidence\beta-online-e2e.json `
  -AllowLegacyRcUpgrade `
  -PreflightOnly
```

Preflight validates the deployed Beta manifest, artifact hashes and embedded
build metadata, source commit/branch, evidence binding, channel isolation, and
the existing RC runtime without creating a branch, worktree, commit, build,
manifest, or shortcut. It must print `PREFLIGHT_OK` before the real command is
allowed:

```powershell
pwsh -File .\promote-beta-to-release-candidate.ps1 `
  -VerificationEvidencePath .\evidence\beta-online-e2e.json `
  -AllowLegacyRcUpgrade
```

The evidence file must contain `status: PASS`, `releaseChannel: beta`, the
exact `sourceBranch`, `sourceCommit`, `artifactName`, and `artifactSha256`,
`gamesCompleted >= 3`, `terminalMarkers >= 3`, `scriptProcessStable: true`,
and an existing `finalScreenshotPath`. Omitting the file or presenting proof
for another build is a hard refusal. The script also accepts `-ShortcutRoot`
for offline contract tests; normal promotion verifies the real Desktop, Start
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
existing worktree at an unexpected path, a missing Beta manifest, a missing or
stale E2E evidence file, an embedded JAR version/channel/timestamp mismatch, an
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
