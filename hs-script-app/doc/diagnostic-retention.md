# Diagnostic retention and Beta storage layout

## Policy

The application runs `DiagnosticRetentionService` once at startup and every 6 hours afterward. The release path also installs and verifies a per-channel Windows Scheduled Task that invokes the deployed `diagnostic-retention.ps1` at 00:00, 06:00, 12:00, and 18:00, plus at user logon. `StartWhenAvailable` catches up a missed trigger after sleep or scheduler unavailability. The task uses the current user's limited interactive token, stores no password, and launches hidden PowerShell without showing an app UI or starting Hearthstone. This works while a channel app is closed as long as the user's Windows session remains logged in. The task cannot run while that user is signed out; its logon trigger and missed-run catch-up resume cleanup after sign-in. The release entrypoint is versioned with the runtime and the task action is checked against that exact runtime script.

The cleaner removes only individual diagnostic files whose last-write time is strictly earlier than the 30-day cutoff. Files exactly at the cutoff remain. A file that crosses the cutoff just after a scan can remain until the next scan (at most 6 hours while the machine is available); a powered-off machine delays the next scan until the scheduler or user session becomes available, when missed work is run. Fresh files are retained using traversal metadata only, so neither cleaner opens or probes locks on fresh files. Only expired, non-active files are probed for an exclusive lock before deletion. Current app logs, Beta guardian and launcher-selection state, and files that cannot be exclusively opened are retained. Deletion failures are logged and retried on the next run. The Java and PowerShell entrypoints use matching root, file-extension, and subdirectory allowlists.

Each PowerShell run persists its timestamp, channel, cutoff, deleted count/bytes, and error count to an hourly `diagnostic-retention-YYYYMMDD-HH.log` under that channel's approved runtime log root (`log` or Beta's direct `runtime-log`). The summary is excluded from the active cleanup pass and follows the same 30-day file policy. Each hourly file is capped around 256 KiB by retaining its most recent 100 lines when rotating; no summary is written outside the channel root. This gives Task Scheduler runs durable evidence even when stdout is discarded.

Until a release containing `diagnostic-retention.ps1` has been deployed and its task registration verified, only the app-start and while-running Java cleanup is active; that state does not enforce a 30-day maximum while the app is closed. The release owner must verify `DIAGNOSTIC_RETENTION_TASK_VERIFIED` for each installed channel before treating offline cleanup as enabled.

The cleaner has an explicit root and file-type allowlist. All channels scan their runtime `log`, `evidence`, `ocr_res`, and `debug\mouse` roots. Evidence accepts the diagnostic text/log/state/JSON/JSONL/CSV/TSV/image extensions; OCR accepts PNG only; debug mouse traces accept CSV and PNG only. It does not scan the whole runtime, `resources`, or backup directories. Beta additionally scans its direct runtime log path, external Beta log root and `app` logs, plus known diagnostic categories under the configured artifact root. It does not remove arbitrary binaries or traverse symlinks, junctions, or other reparse points. It deletes no directories. Missing, inaccessible, locked, or reparse roots are recorded and skipped.

The cleaner never scans the runtime root as a whole. `config`, `data` and `statistics.db`, `plugin`, `lib`, packaged/runtime binaries, deployment manifests, rollback backups, Hearthstone's own `Power.log`/`Decks.log`/`LoadingScreen.log`, and files outside approved diagnostic roots remain outside the deletion scope.


The shared Hearthstone client log directory `D:\Hearthstone\Logs` is an additional exact root for all three channel cleaners. Only `.log` files older than 30 days are eligible; current/fresh files and files that cannot be opened exclusively are retained. Reparse points are skipped, and no sibling such as `D:\Hearthstone\LogsBackup` or other path under `D:\Hearthstone` is scanned. Stable, Beta, and Release Candidate evidence roots remain channel-specific. Candidate inventory for this shared root is pending; no production files were inspected or removed during the source change.
## Channel activation and current roots

Cleanup runs independently in each installed application. A Stable process cleans only `Hearthstone Script\log`; a Release Candidate process cleans only `Hearthstone Script Release Candidate\log`; a Beta process cleans its own `Hearthstone Script Beta\log`, the Beta log root selected by `hs.script.log.dir`, and the diagnostic categories selected by `hs.script.artifact-root`. This allows separate channel deployment schedules and avoids one channel modifying another channel's runtime.

The current Beta launcher stores active application logs under `D:\HearthstoneScriptLogs\Beta\FD3DED65832A61AFA24A40FC\app` and screenshots/replay artifacts under `D:\HearthstoneScriptArtifacts\Beta\FD3DED65832A61AFA24A40FC`. The existing `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta\log` tree still contains historical diagnostics. Existing external Beta roots are already on D:, but the large runtime `log` tree is not yet relocated. After relocation, the launcher passes `hs.script.beta-runtime-log-root` as `D:\HearthstoneScriptLogs\Beta\FD3DED65832A61AFA24A40FC\runtime-log`; the cleaner continues to cover the C: directory before relocation, refuses it once it becomes a junction, and scans the explicit direct D: path.

## Read-only runtime inventory (2026-10-27)

The inventory below counts only allowlisted extensions beneath the exact approved roots, using a strict 30-day last-write cutoff. Counts are candidates before active-path, lock, access, and reparse checks; they are not a projected deletion yield. No files were deleted.

| Channel/root | Eligible files | Old candidates | Candidate bytes |
| --- | ---: | ---: | ---: |
| Stable `log` | 13,277 | 8,401 | 20,180,979,711 |
| Stable `evidence` | 178 | 178 | 62,793,770 |
| Stable `ocr_res` (PNG only) | 21 | 21 | 6,033,121 |
| Stable `debug\mouse` (CSV/PNG only) | 2 | 2 | 24,750 |
| Release Candidate `log` | 4,809 | 0 | 0 |
| Beta C: `log` | 8,594 | 1,010 | 684,327,729 |
| Beta D: external logs | 2 | 0 | 0 |
| Beta D: artifact categories | 177 | 0 | 0 |
| **Total** | **27,060** | **9,612** | **20,934,159,081** |

Release Candidate and Beta have no current `evidence`, `ocr_res`, or `debug\mouse` files. Stable `resources` had six old image files, but they are application assets and are excluded; `rollback-backups`, `runtime-backups`, `plugin-backups`, and `lib-backups` are also excluded.

## Beta C: to D: relocation plan

Do this only as a separately coordinated, stopped-runtime maintenance action after the Beta release owner records the deployment/process/log lineage. Keep the current D: external roots intact. Create `D:\HearthstoneScriptLogs\Beta\FD3DED65832A61AFA24A40FC\runtime-log`, then copy the C: `log` contents while Beta is stopped. Verify file count, byte count, and a content hash manifest before changing the source. Rename the original C: `log` to a dated rollback directory, create a directory junction at the original `...\Hearthstone Script Beta\log` path pointing to the new D: directory, then verify the junction target and representative old paths. The deployed Beta launcher must pass the direct D: path in `hs.script.beta-runtime-log-root`; do not make the cleaner follow the junction. Keep the renamed C: copy until the next verified Beta deployment and retention run. Never move an open log, remove the old tree before hash verification, or replace a Beta, Stable, or Release Candidate path with a link into another channel.

The external Beta log root currently has `app`, `guardian-state.json`, and `launcher-selection.log`; the external artifact root has `debug-screenshots`, `game-results`, and `unknown-states`. The application can also create `mcts-replay`, `mcts-deck-profile`, `mulligan`, and other known diagnostic categories. The proposed junction should preserve the existing C: `log` path for callers and old evidence links while the Beta launcher continues to use the external D: app-log property. Do not execute the relocation as part of a source change or release build.

## Prevention / review gotchas

- Add diagnostic outputs only inside an explicitly listed log/artifact root and use a retained diagnostic extension; do not put configs, databases, plugin state, or runtime files beneath those roots.
- Keep channel runtime, external log, and artifact roots distinct. Beta must retain its deployment-specific runtime key so one Beta installation cannot clean another's evidence.
- Before changing roots or retention behavior, test the exact 30-day boundary, active-file skip, deletion retry, missing-root path, and reparse-point fence. Never use an unrestricted recursive delete over a channel runtime.
- The retention hook only takes effect in an installed channel after that channel receives the new build and starts. No existing files are removed by source checkout, compile, or test execution.
