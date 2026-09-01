# Overnight integration ledger — 2026-09-01

Coordinator worktree: `C:/Users/yzjsh/.codex/worktrees/ab8c/Hearthstone Copilot`

Coordinator branch: `codex/overnight-release-integration`

Current checkpoint: pending commit (runner `44921340` on readiness/sentinel lineage
`e53685c4`, plus OCR provider contract fix `1d64115a`). Worktree is intended to
remain clean between evidence checkpoints. No canonical runtime or E2E credit
is assigned to this source until a higher-version single-tree deployment is
verified.

## Ordered queue

1. **Offline integration gate — passed; release-script correction pending**
   - Verify OCR provider regression tests together with readiness, DebugRun
     lease, runner contract, Power.log, and action guards.
   - The combined test selection passed 85/85 with 0 failures and 0 errors.
     Reports are under `hs-script-app/target/surefire-reports`.
   - The inherited checked-in release script named a nonexistent
     `PaddleXRankDetectorTest`; it is being corrected to use only tests present
     in this complete tree and to include the OCR/runner guard tests.

2. **Release/CU mutex — blocked pending reconciliation**
   - Canonical manifest currently points to v4.16.135-local-20260901-011404PDT,
     SHA-256 `6fccd713db787691bb497659283cd3dbd80e00d6049ab12f603d1352fc795545`.
   - That artifact matches readiness owner target output and does not contain
     the new OCR config-reconciliation marker. It is excluded from freeze.
   - Before deployment, safely close stale Hearthstone PID `12340`, verify no
     Java/runner remains, then use one release slot to deploy a strictly higher
     version from this tree.

3. **Fresh DebugRun E2E — queued**
   - Start only after manifest/JAR/ZIP/runner/shortcut provenance is verified.
   - Every attempt gets a new run id and per-run ledger. Preserve all failed
     artifacts and append a gotcha on invalidation.
   - Completion requires two consecutive valid real games, at least one
     current-player WON in the same build/evidence lineage, and zero errors,
     exceptions, timeouts, OCR failures, UNKNOWN/manual recovery, or missing
     lifecycle/exit evidence.

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
