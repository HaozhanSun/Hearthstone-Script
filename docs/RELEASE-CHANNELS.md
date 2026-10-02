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
- Beta shortcuts use `hs-script-beta.ico`, which is generated from the stable
  application icon with a visible `B` badge. Stable shortcuts continue to use
  the application icon embedded in `hs-script.exe`.
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
runtime and shortcut separation. Before making any Beta runtime changes, the
Beta release path reads Explorer's Taskband `FavoritesResolve` data and requires
the exact canonical `User Pinned\TaskBar\Hearthstone Script Beta.lnk` path to be
registered. Finding a valid `.lnk` file in the Taskbar folder is not sufficient:
Explorer may still hold a pin entry for a shortcut that was moved elsewhere.
The script updates an already-registered Taskbar link in place and never edits
Taskband registry data, deletes/unpins a link, or silently creates a pin.

If the preflight fails, stop before runtime files or shortcuts are modified.
The secretary/operator must manually restore the pin through Windows UI: open
the Beta runtime's `launch-as-admin.vbs` (or its Start Menu shortcut), then use
the app/window's Windows taskbar context menu to pin it. If Windows policy
prevents pinning, leave deployment blocked and report that policy; do not bypass
it with registry edits or unsupported APIs. Once restored, rerun deployment and
verify `FavoritesResolve` now names the canonical Taskbar `.lnk`.

After deployment, the secretary must also visually confirm the Beta taskbar icon
is present, click that actual icon, and verify the launched app footer and fresh
log identify the exact manifest version/deployment ID. Registry registration
and shortcut metadata are structural checks, not proof that the icon rendered or
that clicking it launched the selected deployment. Keep this UI launch evidence
as a separate required Beta release sign-off.

Windows documents `TaskbarManager.RequestPinCurrentAppAsync` as a request that
displays a user confirmation, requires explicit user interaction and a
foreground app, and must not be invoked by installers. See Microsoft's
[Taskbar pinning guidance](https://learn.microsoft.com/en-us/windows/apps/develop/windows-integration/pin-to-taskbar).

The deployment manifest records the channel, runtime root, and shortcut name so
stale beta artifacts cannot be selected by a stable launcher.

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
