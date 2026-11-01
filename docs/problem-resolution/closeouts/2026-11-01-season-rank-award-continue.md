# Problem-resolution investigation record

Issue: 2026-11-01 · season-rank-award Continue dispatch
Date: 2026-11-01
Status: OPEN — live correlation is pending a secretary-owned .737 diagnostic deployment; this record does not close the issue.

Investigation and cause: PARTIAL — The v4.16.736 log proves a fresh `SEASON_RANK_AWARD` frame, an empty current Power.log, `pause=false`, `HUB`, and repeated `dispatch-revalidation-failed` blocks. The old log does not identify which former predicate failed because all predicates were collapsed into one message. The saved frame also shows a red crossed connection indicator, so connectivity/session state must be correlated during the diagnostic run. No claim is made that the old live predicate has been fixed.

Fix: DIAGNOSTIC-ONLY — `SeasonRankAwardRecoveryPolicy.ContinueDispatchContext` and the `MouseUtil` Continue path now report the exact ordered predicate and stage at each final revalidation boundary. The dedicated action remains limited to a fresh, exact season-award Continue and retains every gameplay, queue, terminal, rank, process, window, working, and pause fence.

Verification: PARTIAL — The focused local reactor suite passed 55 tests with zero failures/errors/skips (`season-award-focused.log`), including empty-Power.log authorization and every policy rejection branch. Live .736 behavior has not been rerun with this diagnostic code; the issue remains open pending .737 evidence.

Future prevention: PASS — Preserve structured reason/stage logging for every final Continue rejection and require a fresh exact frame plus current PID/HWND authority before any input.

Prevention enforcement: PASS — test: `SeasonRankAwardRecoveryDispatchTest.continue boundary has an explicit reason for every ordered rejection branch` and the existing focused suite; these regressions reject missing scope, process/window mismatch, unsafe session, gameplay, terminal, queue, rank, pause, and working-state fences.

Next action: Secretary deploys the diagnostic .737 build, captures the first structured `SEASON_RANK_AWARD_CONTINUE_BLOCKED` reason/stage, and verifies whether the exact Continue click is accepted without authorizing generic or gameplay input.
