# Process evidence — 2026-08-30 regression investigation

This is a factual supplement to the read-only postmortem. It records the
safe-stop observations and the persisted stop-after capture. No process was
force-killed because the target automation was already absent.

## Before-stop observation

- Capture time: 2026-08-30 approximately 12:19 PDT.
- The target Hearthstone Copilot `java`/`javaw`, `wscript`, `cscript`, Maven,
  and build/deploy process list was empty; there was no script process to stop.
- Hearthstone was intentionally retained:
  - PID `165148`, parent PID `165468`
  - command line: `"D:\\Hearthstone\\Hearthstone Beta Launcher.exe" -uid hs_beta`
  - created: `2026-08-30 01:53:06 PDT`
- The logical app PID seen in the incident log was `160864`; it was not an
  active OS process in the stop observation.
- The raw before-stop console capture was not persisted as a standalone file;
  this document records the observed facts without reconstructing or claiming
  a raw process-tree dump that is no longer available.

## After-stop observation

- Capture time: 2026-08-30 12:30:30 PDT.
- Persisted broad process query:
  `%TEMP%\\hearthstone-regression-stop-after.txt`
  (`C:\\Users\\yzjsh\\AppData\\Local\\Temp\\hearthstone-regression-stop-after.txt`).
  It included unrelated Codex tool `cmd.exe` processes because of the broad
  diagnostic filter; those were not touched.
- Focused target-runtime checks after deployment and before handoff found:
  `java.exe/javaw.exe/wscript.exe/cscript.exe` for Hearthstone Script:
  `NONE`.
- `hs-script.pid.json` was absent, so no managed script PID remained.
- Hearthstone PID `165148` remained present and was not terminated.

## Preserved paths and incident artifacts

- Runtime `config`, `data`, `log`, and `plugin` directories remained present.
- The runtime had no `backups` directory at verification time; no backup path
  was deleted.
- The incident artifacts remain at the exact paths recorded in
  `docs/POSTMORTEM-20260830-regressions.md`, including `hs_script.log` and the
  selected `Power.log`.
