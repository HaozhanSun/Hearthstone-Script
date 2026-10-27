# Diagnostic retention and Beta storage layout

## Policy

Each installed channel runs `DiagnosticRetentionService` once during application startup and every 24 hours afterward on a daemon worker. It removes only individual diagnostic files whose last-write time is strictly earlier than the 30-day cutoff. Files exactly at the cutoff remain. The current app log, current Beta guardian state, launcher selection log, current E2E milestone file, and any file that cannot be exclusively opened are retained. Deletion failures are logged and retried on the next run.

The cleaner has an explicit root and file-type allowlist. For Stable and Release Candidate it scans only that installation's `log` tree. For Beta it scans the runtime `log` tree, the external Beta log root and its `app` logs, plus known diagnostic categories under the configured artifact root. It accepts text/log/state/JSON/JSONL/CSV/TSV and PNG evidence files; it does not remove arbitrary binaries or traverse symlinks, junctions, or other reparse points. It deletes no directories. Missing, inaccessible, locked, or reparse roots are recorded and skipped.

The cleaner never scans the runtime root as a whole. `config`, `data` and `statistics.db`, `plugin`, `lib`, packaged/runtime binaries, deployment manifests, rollback backups, Hearthstone's own `Power.log`/`Decks.log`/`LoadingScreen.log`, and files outside approved diagnostic roots remain outside the deletion scope.

## Channel activation and current roots

Cleanup runs independently in each installed application. A Stable process cleans only `Hearthstone Script\log`; a Release Candidate process cleans only `Hearthstone Script Release Candidate\log`; a Beta process cleans its own `Hearthstone Script Beta\log`, the Beta log root selected by `hs.script.log.dir`, and the diagnostic categories selected by `hs.script.artifact-root`. This allows separate channel deployment schedules and avoids one channel modifying another channel's runtime.

The current Beta launcher stores active application logs under `D:\HearthstoneScriptLogs\Beta\FD3DED65832A61AFA24A40FC\app` and screenshots/replay artifacts under `D:\HearthstoneScriptArtifacts\Beta\FD3DED65832A61AFA24A40FC`. The existing `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta\log` tree still contains historical diagnostics. Existing external Beta roots are already on D:, but the large runtime `log` tree is not yet relocated. After relocation, the launcher passes `hs.script.beta-runtime-log-root` as `D:\HearthstoneScriptLogs\Beta\FD3DED65832A61AFA24A40FC\runtime-log`; the cleaner continues to cover the C: directory before relocation, refuses it once it becomes a junction, and scans the explicit direct D: path.

## Beta C: to D: relocation plan

Do this only as a separately coordinated, stopped-runtime maintenance action after the Beta release owner records the deployment/process/log lineage. Keep the current D: external roots intact. Create `D:\HearthstoneScriptLogs\Beta\FD3DED65832A61AFA24A40FC\runtime-log`, then copy the C: `log` contents while Beta is stopped. Verify file count, byte count, and a content hash manifest before changing the source. Rename the original C: `log` to a dated rollback directory, create a directory junction at the original `...\Hearthstone Script Beta\log` path pointing to the new D: directory, then verify the junction target and representative old paths. The deployed Beta launcher must pass the direct D: path in `hs.script.beta-runtime-log-root`; do not make the cleaner follow the junction. Keep the renamed C: copy until the next verified Beta deployment and retention run. Never move an open log, remove the old tree before hash verification, or replace a Beta, Stable, or Release Candidate path with a link into another channel.

The external Beta log root currently has `app`, `guardian-state.json`, and `launcher-selection.log`; the external artifact root has `debug-screenshots`, `game-results`, and `unknown-states`. The application can also create `mcts-replay`, `mcts-deck-profile`, `mulligan`, and other known diagnostic categories. The proposed junction should preserve the existing C: `log` path for callers and old evidence links while the Beta launcher continues to use the external D: app-log property. Do not execute the relocation as part of a source change or release build.

## Prevention / review gotchas

- Add diagnostic outputs only inside an explicitly listed log/artifact root and use a retained diagnostic extension; do not put configs, databases, plugin state, or runtime files beneath those roots.
- Keep channel runtime, external log, and artifact roots distinct. Beta must retain its deployment-specific runtime key so one Beta installation cannot clean another's evidence.
- Before changing roots or retention behavior, test the exact 30-day boundary, active-file skip, deletion retry, missing-root path, and reparse-point fence. Never use an unrestricted recursive delete over a channel runtime.
- The retention hook only takes effect in an installed channel after that channel receives the new build and starts. No existing files are removed by source checkout, compile, or test execution.
