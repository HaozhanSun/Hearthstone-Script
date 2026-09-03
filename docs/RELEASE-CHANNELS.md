# Stable and beta release channels

This repository has two intentionally separate release channels.

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
- Beta is allowed to fail or change behavior while it is under investigation;
  it must never be promoted to `main` merely because it compiles.
- CI labels beta artifacts as beta and validates that the branch metadata and
  runtime identity are consistent.

## Promotion and rollback

1. Start from the last known-good stable commit and record the working-version
   comparison when repairing a regression.
2. Develop and test on `beta/*` in an isolated worktree.
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
