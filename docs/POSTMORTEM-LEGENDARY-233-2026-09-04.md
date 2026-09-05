# Legendary 233 rank-detection postmortem

Date: 2026-09-04  
Base: Beta `.199`, release commit `cc5168df`  
Repository: `https://github.com/HaozhanSun/Hearthstone-Script.git`  
Worktree: `C:\Users\yzjsh\.codex\worktrees\1139\Hearthstone Copilot`

## Required source comparison (before code changes)

The known-working refs inspected before this investigation are:

- `de0fd579d015db7052a9effd1aa959966425e9c6`, fetched directly from
  `origin` (multi-project/submodule-era tree).
- `e5d26c8698f1691f1523fd070a8bbd16680f36eb` (`v4.16.3-GA` working ref).

The directly relevant legacy files are
`src/main/java/club/xiaojiawei/hsscript/strategy/phase/ReplaceCardPhaseStrategy.kt:25-48`
and
`src/main/java/club/xiaojiawei/hsscript/utils/GameUtil.kt` (the fixed
`SURRENDER_RECT`). Legacy phase code evaluated `WarEx.winStreak`/win-rate at
`MULLIGAN_STATE=INPUT` and directly called `GameUtil.surrender()`; it had no
Legendary badge classifier and no PaddleX/ScreenStateRecovery path.

## Reproduction input and current `.199` path

Input screenshot (not counted as an online run):

`C:\Users\yzjsh\AppData\Local\Temp\codex-clipboard-87b759ac-14fb-40d2-9bc4-9247d2c20ac2.png`

It is `1919x1079` and visibly shows a settlement/defeat screen with the
player badge and rating `233`. The small color reference is:

`C:\Users\yzjsh\AppData\Local\Temp\codex-clipboard-9d4f4025-4999-4665-bda1-88b1f487adee.png`

At the base code, `CurrentRankDetector.kt:38-46` uses the numeric badge ROI
`left=0.0, top=0.82, width=0.075, height=0.13`, which maps to
`x=0,y=884,w=143,h=140` for this input. That crop includes the complete
left-side badge, but the current visual classifier at
`CurrentRankDetector.kt:342-372` only recognizes warm Gold or neutral Silver;
it has no Legendary result. The rank parser accepts only constructed ranks
`1..10`, so `233` is intentionally not a valid numeric rank.

The current OCR path is `CurrentRankDetector.detectCapturedImage()`
(`CurrentRankDetector.kt:196-299`) -> `TesseractEx.doOCR()` -> `OcrRuntime`.
With the default PaddleX-first mode, the provider label is PADDLEX. The
authoritative Beta log failure chain is:

- `hs_script.log:7680`: `OCR_PROVIDER_USED provider=PADDLEX desc=rank-policy-REPLACE_CARD chars=0 nativeConfidence=unavailable`
- `hs_script.log:7681`: `RANK_OCR ... roi=x23,y941,w57,h47 raw=<empty> normalized=<empty> tier=UNKNOWN rank=UNKNOWN unknownReason=empty-text-and-tier`
- `hs_script.log:7682-7684`: `UNKNOWN_FAIL_CLOSED` evidence is written for that ROI.
- `hs_script.log:7685-7686`: despite that failure classification, the policy logs
  `rank-ocr-unresolved-surrender detectionAvailable=true tier=UNKNOWN` and
  `SURRENDER_ACTION_REQUESTED`.
- `hs_script.log:7702`: `TERMINAL_RESULT_FALLBACK outcome=LOST source=local-surrender-request`.
- `hs_script.log:7750-7752`: the settlement/terminal screenshot is later fed
  to screen watchdog OCR, which logs `provider=PADDLEX kind=UNKNOWN
  action=STOP_SURRENDER_AND_PAUSE_UNKNOWN` and `SCREEN_WATCHDOG_BLOCKED`.

The corresponding watchdog screenshot is
`C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta\log\unknown-states\screen-watchdog\2026-09-04\unknown-state-20260904-131032-989-screen-watchdog-normal-surrender-retry-2f2b75b1-efa0-43a3-b113-9d1844c18d05.png`.
It is a real `REPLACE_CARD`/active-game frame with the Legendary 233 badge.
The supplied full screenshot is a separate later settlement/defeat frame and
must not be conflated with it.

The precise offline structured run, including the real PaddleX raw/normalized
text, provider contract result, current detector output, and all crop paths,
is saved by the diagnostic harness before the implementation change.

The final visual probe is intentionally smaller than the original diagnostic
crop: at 1920x1080 it is approximately `x=0,y=885,w=105,h=108`. Numeric OCR
continues to use the independent narrow `x=23,y=941,w=57,h=47` ROI. The
smaller visual crop removes the player-name pixels and excess lower background
without clipping the active Legendary badge.

## Root cause hypothesis to verify offline

The failure is not a Gold-versus-Platinum rule issue. The complete badge is
outside the former narrow numeric assumptions for the rating value, and the
classifier has no independent Legendary visual path. A non-numeric rating
therefore becomes `rank=null`, `tier=UNKNOWN` and is consumed by the
fail-closed unknown branch. The fix must add independent, badge-local
Legendary geometry/color evidence and must not convert the rating into rank
7, rank 10, or an OCR fallback. Provider failure remains observable and
fail-closed unless the independent Legendary evidence is strong enough to
produce the explicit `LEGENDARY` classification.

The screenshot is also a terminal settlement/defeat frame. Even when the
rank detector returns `LEGENDARY` for classification evidence, the existing
authoritative terminal-state guard in `SurrenderPolicy.kt:448-454` must win
and no game action may be sent.

## Acceptance evidence required after the fix

The retained offline artifact directory will be:

`C:\Users\yzjsh\.codex\worktrees\1139\Hearthstone Copilot\artifacts\legendary-diagnostic-20260904`

It must contain the current ROI crop, the expanded Legendary probe crop, and
a structured result recording provider, raw/normalized OCR, ROI coordinates,
HSB/color ratios, geometry metrics, final rank/tier and policy decision. The
same harness will cover ordinary numeric rank, invalid red/partial color,
and a complete Legendary badge. No artifact or offline result is an online
E2E win.

## Evidence addendum: terminal priority

The full sample is explicitly a settlement/terminal frame: the center reads
“败北/点击继续”, while the player's Legendary badge and rating `233` are at
the lower-left. The harness must therefore report two separate outcomes:

1. rank classification may be `LEGENDARY` when the badge evidence is strong;
2. the policy decision for this captured frame is `NO_ACTION` because an
   authoritative terminal/settlement state outranks surrender.

Additional offline cases must show that a Legendary badge during an eligible
`REPLACE_CARD` rank inspection is not converted to `UNKNOWN` or a numeric
rank, that an eligible rank frame with no usable number or Legendary evidence
requests surrender, and that a pre-rank or non-rank frame does not request
surrender.

## Guard audit (current source, not inferred from the old report)

The persistent streak guard is still present in the current worktree:
`SurrenderPolicy.kt:96-98` defines `MAX_CONSECUTIVE_SURRENDERS=7` and
`MAX_CONSECUTIVE_WINS=5`; `:235-257` reconstructs the trailing streak from
completed `statistics.db` records sorted by end time. A row with
`surrendered=true` increments only the surrender streak; an explicit
non-surrendered win (`result=true, surrendered=false`) increments only the win
streak; a non-win, unknown surrender flag, or other completed result resets
both counters.

`SurrenderPolicy.kt:260-283` gives the two thresholds deliberately different
semantics. Seven consecutive persisted concessions match
`consecutive-surrenders-over-seven`, return `shouldSurrender=false` and
`blocksAutomaticSurrender=true`; the caller cancels pending work, sets
`PauseStatus.isPause=true`, and does not dispatch surrender. Five consecutive
non-surrendered wins match `consecutive-wins-over-four`, return
`shouldSurrender=true`, and are dispatched as the next game's early surrender.
The rule id says “over-four”, but the actual inclusive threshold is five;
this is a naming mismatch, not a numeric mismatch.

The guard is checked from both early-policy entry points at
`SurrenderPolicy.kt:352-356` and `:450-462`. Authoritative terminal state is
checked first (`:84-89`), so `GAME_OVER`, `FINAL_GAMEOVER`, WON, LOST, or
CONCEDED wins over a late streak decision. The current tests at
`SurrenderPolicyTest.kt:30-113` cover four wins as below threshold, five wins
as surrender, seven surrenders as block/pause, and reset behavior; the added
boundary test also covers six surrenders as below threshold.

The old regression evidence remains a real execution failure, not a condition
failure: `docs/POSTMORTEM-20260830-regressions.md:86-100` records
`consecutive-wins=13 threshold=5 action=CONTINUE surrenderPolicyPass=SKIPPED`.
The current source no longer has that recovery-continue path: a matched win
guard returns the surrender result, while a matched surrender guard pauses.
There is no current Beta `hs_script.log` line proving a matched streak during
the interrupted run; therefore this audit is source/test evidence, not online
E2E evidence.

## Probe result after the local fix

The first two harness attempts failed as expected because the broad badge
projection merged the three rating glyphs into one segment and returned
`tier=GOLD`, `rankGate=PAUSE`. After changing the projection to require broad
bright-glyph coverage rather than artificial inter-glyph gaps, the structured
PaddleX-backed harness passed both cases (`2 tests, 0 failures`):

- Active screenshot:
  `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta\log\unknown-states\screen-watchdog\2026-09-04\unknown-state-20260904-131032-989-screen-watchdog-normal-surrender-retry-2f2b75b1-efa0-43a3-b113-9d1844c18d05.png`
  produced PaddleX raw `233Iaz恶魔`, `tier=LEGEND`, `rank=UNKNOWN`,
  `rankGate=ALLOW reason=legendary-badge-confirmed`, with visual metrics
  `warm=4415/13110`, `activeColumns=71`, `digitSpan=77`.
- Settlement screenshot:
  `C:\Users\yzjsh\AppData\Local\Temp\codex-clipboard-87b759ac-14fb-40d2-9bc4-9247d2c20ac2.png`
  produced PaddleX raw `233Iaz`, the same Legendary classification, but the
  harness reported `policyAction=NO_ACTION` because authoritative settlement
  state outranks surrender.

Structured reports and crops are retained under
`hs-script-app\artifacts\legendary-diagnostic-20260904\active-replace-card`
and `...\settlement-terminal`. These are offline/source-regression results;
`E2E_GATE` remains `UNMET` until the secretary performs the separately gated
current-build online verification.
