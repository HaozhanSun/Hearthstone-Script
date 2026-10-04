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
