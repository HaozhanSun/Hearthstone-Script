# Overnight integration ledger — 2026-09-01

Coordinator worktree: `C:/Users/yzjsh/.codex/worktrees/ab8c/Hearthstone Copilot`

Coordinator branch: `codex/overnight-release-integration`

Current checkpoint: `bdd63ba7` adds the hub visual recovery guard on top of the
single-tree release lineage. The latest canonical deployment is v4.16.138;
the worktree is intended to remain clean between evidence checkpoints. No E2E
credit is assigned until real-game evidence passes the completion gate.

## Ordered queue

1. **Offline integration gate — passed**
   - Verify OCR provider regression tests together with readiness, DebugRun
     lease, runner contract, Power.log, and action guards.
   - The combined test selection passed 85/85 with 0 failures and 0 errors.
     Reports are under `hs-script-app/target/surefire-reports`.
   - The checked-in release script uses only tests present in this complete
     tree and includes the OCR/runner/screen-recovery guard tests.
   - v4.16.138 checked-in build tests passed 119/119 with 0 failures and 0
     errors; the full 8-module reactor passed BUILD SUCCESS.

2. **Release/CU mutex — v4.16.138 deployed and provenance verified**
   - Canonical manifest currently points to v4.16.135-local-20260901-011404PDT,
     SHA-256 `6fccd713db787691bb497659283cd3dbd80e00d6049ab12f603d1352fc795545`.
   - That artifact matches readiness owner target output and does not contain
     the new OCR config-reconciliation marker. It is excluded from freeze.
   - Before deployment, safely close stale Hearthstone PID `12340`, verify no
     Java/runner remains, then use one release slot to deploy a strictly higher
     version from this tree.

   - v4.16.137 release slot completed successfully after the v4.16.136 runner
     packaging miss was fixed. Checked-in release tests: 118/118, 0 failures,
     0 errors. Target and canonical JAR SHA-256:
     `fb1b2fec401b2331aaa4ebc61230e0ab882c7ddac0a5a0dfa750711cf4e66bf6`.
     Target and canonical ZIP SHA-256:
     `4d119e7cc9b83e74ba2344708b1bfce5b59a37681a7b5a03c6fbf32894a03e40`.
   - Source/deployed `run-debug.ps1` SHA-256:
     `f5cb33ff2c81c85e02dfeba62a5e304a082b56e902346cf30eaa64d7c0cb4743`;
     ZIP contains `run-debug.ps1` and the selected JAR. JAR marker inspection
     confirms OCR config reconciliation, fresh `Power.log`/`CREATE_GAME`
     readiness, and sentinel HWND handling.
   - Desktop, Start Menu, and Taskbar shortcuts all target
     `C:/Windows/System32/wscript.exe` with canonical
     `launch-as-admin.vbs`. Process gate was clear after deployment.
   - v4.16.138 manifest selected
     `hs-script_v4.16.138-local-20260901-015730PDT.jar` with JAR SHA-256
     `facb17909e2656ff750ce49a0bf25f1e5d988dc7dff0f4ef85f68f9c9e1b99b5`.
     Target and canonical JAR hashes match. ZIP SHA-256 is
     `ea1992d6d5ab1dbea821a15b20fa71878a5bfec069ff78c91e3664ff60c9ab26`.
     The ZIP contains both the selected JAR and `run-debug.ps1`; the runner
     SHA-256 remains
     `f5cb33ff2c81c85e02dfeba62a5e304a082b56e902346cf30eaa64d7c0cb4743`.
     Class markers confirm OCR reconciliation, hub visual recovery, fresh
     Power.log/CREATE_GAME readiness, and sentinel HWND handling.

3. **Fresh DebugRun E2E — ready to start on v4.16.138**
   - Start only after manifest/JAR/ZIP/runner/shortcut provenance is verified.
   - Every attempt gets a new run id and per-run ledger. Preserve all failed
     artifacts and append a gotcha on invalidation.
   - Completion requires two consecutive valid real games, at least one
     current-player WON in the same build/evidence lineage, and zero errors,
     exceptions, timeouts, OCR failures, UNKNOWN/manual recovery, or missing
     lifecycle/exit evidence.
   - Attempt `17598_2257` was invalidated before game start: v4.16.138
     correctly recognized the real hub (`hubNavigationBright=0.182`) and
     Legacy OCR accepted 72–74 characters, but the new readiness guard also
     blocked the pre-game Hub-to-match dispatch. Fresh Power.log remained
     empty; the exact Java/wrapper/Hearthstone processes were stopped after
     command-line validation. Preserve this run and exclude it from credit.

4. **MCTS/Pirate workstreams — locked**
   - Do not start or merge while the release/CU mutex and E2E gate are open.

## Source evidence

- OCR owner: `1d64115adb9bf73b3af07ba1c8dfe307eb20336c`, clean independent
  worktree; focused OCR contract tests reported 15/15.
- Readiness/sentinel: `e53685c4` with sentinel guard `098a2d77`.
- DebugRun runner: source commit `456ba0df27e69b833916b268fed12d6de18bdbdc`,
  integrated here as `44921340`.
- Prior invalid run: `47710_5759`; it remains excluded because Legacy OCR was
  contract-rejected, fresh Power.log readiness never reached CREATE_GAME, and
  no valid game evidence exists.
- Prior invalid run: `17306_2083`; it remains excluded because the hub was
  visually misclassified as loading and no game was created.
