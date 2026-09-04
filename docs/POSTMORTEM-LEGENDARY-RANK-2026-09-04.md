# Legendary rank detection postmortem and original-version comparison

Date: 2026-09-04  
Worktree: `codex/beta-rank-integration-20260904`  
Current source checkpoint: `50ba6a3e`  
Current deployed Beta checkpoint: `v4.16.198-local-20260904-113000PDT`

## Evidence and reproduced failure

The supplied screenshots are:

- `C:\Users\yzjsh\AppData\Local\Temp\codex-clipboard-6776757e-df10-4931-a627-2ef7a2f188b3.png` (163x168): a Legendary badge with the visible rating `230`.
- `C:\Users\yzjsh\AppData\Local\Temp\codex-clipboard-9d4f4025-4999-4665-bda1-88b1f487adee.png` (38x35): a close-up of the badge's warm metal edge.

The first image contains no constructed rank in the accepted 1..10 numeric domain. Its visible number is a Legendary rating, not a rank number. The current detector therefore returns `rank=null`; before this change the rank policy enters `classifyRankInspection()` and eventually emits `rank-ocr-unresolved`, pauses, and never reaches a safe continue decision.

The latest historical Beta evidence at `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta\log\unknown-states\rank-detection\2026-09-04\unknown-state-20260904-090802-711-rank-policy-REPLACE_CARD-RANK_RESOLVED-4676613d-9ea1-4d27-a465-9175e58e81a6.png` demonstrates the normal numeric path: `roi=x23 y941 w57 h47`, `rawOCR=2`, `numericRank=2`, `tier=SILVER`, and `finalDecision=RANK_RESOLVED`. The adjacent failure sample at `...090754-908...UNKNOWN_FAIL_CLOSED...png` demonstrates that genuinely unresolved rank evidence must continue to fail closed.

## Exact original refs inspected

The repository is `https://github.com/HaozhanSun/Hearthstone-Script.git` (`origin`). The exact refs inspected were:

- `de0fd579d015db7052a9effd1aa959966425e9c6`, fetched directly from `origin` into `FETCH_HEAD`; this ref is a multi-project/submodule-era tree and does not contain the current rank detector.
- `e5d26c8698f1691f1523fd070a8bbd16680f36eb` (the recorded `v4.16.3-GA` working ref); this is the single-module legacy implementation.

At `e5d26c8698f1691f1523fd070a8bbd16680f36eb`, the directly relevant files are:

- `src/main/java/club/xiaojiawei/hsscript/strategy/phase/ReplaceCardPhaseStrategy.kt:25-48`: on `MULLIGAN_STATE=INPUT`, it reads `MAXIMUM_WIN_RATE_LIMIT` and `MAXIMUM_WIN_STREAK_LIMIT`; if the configured win-rate or `WarEx.winStreak > winStreakLimit` guard matches, it calls `GameUtil.surrender()`, otherwise it calls `changeCard()`.
- `src/main/java/club/xiaojiawei/hsscript/utils/GameUtil.kt`: provides the legacy fixed normalized UI rectangles, including the surrender rectangle; the ref has no Legendary color classifier and no current-rank OCR path.
- `src/main/java/club/xiaojiawei/hsscript/strategy/phase/FillDeckPhaseStrategy.kt` and `ReplaceCardPhaseStrategy.kt`: Power.log phase transitions own the mulligan timing; the rank decision itself was added downstream in the current client.

The comparison is conclusive for the legacy timing/ownership and coordinate baseline, but not for Legendary semantics: the known-working legacy ref has no such feature.

## Current call chain and behavior delta

Current code path:

1. `SurrenderPolicy.evaluateCurrentRankBeforeMulligan()` waits for a confirmed `REPLACE_CARD`/active-war state.
2. `CurrentRankDetector.detect()` captures the game rectangle and calls `detectCapturedImage()`.
3. `detectCapturedImage()` crops the normalized badge ROI (`CurrentRankDetector.kt:42-45`), runs the selected OCR provider, parses only a complete numeric token in 1..10 (with the existing visual `10` hint), and calls `detectTierVisual()` for diagnostics.
4. If `Detection.rank` is null, the policy now distinguishes an available frame from a provider/capture failure. A confirmed Legendary visual result is a non-numeric terminal state and continues. An available but non-Legendary frame is explicitly classified as `rank-ocr-unresolved-surrender` (no silent continue). A null detection remains the separate bounded retry/fail-closed `rank-ocr-unresolved` pause path.
5. If a numeric rank is available, only 5 and 10 continue; every other confirmed rank, including 7, requests surrender. This is intentionally independent of Gold/Platinum tier.

The regression is specifically the missing non-numeric terminal state. Legendary's rating `230` is correctly rejected by the numeric parser, but the badge evidence is not promoted to `RankTier.LEGEND`; `rank == null` is therefore indistinguishable from a blank/unknown frame. The fix must preserve the numeric-only rule, add a badge-ROI-scoped Legendary visual result, and let only a sufficiently reliable `LEGEND` result continue. Evidence that is weak or outside the badge ROI must remain fail-closed.

## Safety and acceptance criteria

- No rank name or username prefix is used for the decision.
- Numeric ranks remain unchanged: 5 and 10 continue; 1..9 other than 5 request surrender.
- A reliable Legendary badge with no valid 1..10 rank becomes `LEGEND`/non-numeric terminal and continues without surrender or pause.
- An empty, conflicting, or weak badge remains `UNKNOWN_FAIL_CLOSED` and pauses after bounded retries.
- Terminal LOST/WON/settlement and the F2 hard gate remain higher-priority guards.
- Offline fixtures and logs prove classifier behavior; a live run is not counted as complete without current-build authoritative Power.log and screenshot evidence.

## Implemented checkpoint

The current uncommitted checkpoint adds a badge-local HSB probe in
`hs-script-app/src/main/java/club/xiaojiawei/hsscript/status/surrender/CurrentRankDetector.kt`.
The supplied 163x168 Legendary fixture (rating 230) is accepted as
`RankTier.LEGEND`; the supplied 38x35 close-up alone, a red overlay, and a
plain gold badge are rejected. `SurrenderPolicy` consumes this result before
the unknown-rank fallback. The pre-rank readiness gate, terminal-state
priority, F2 hard gate, and provider/capture failure pause path are unchanged.
