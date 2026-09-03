# Rank Detection Replay: 2026-09-03

This is an offline, image-only replay. It uses the configured PaddleX sidecar
and never changes Hearthstone state. The machine-readable result and copied
source/annotated images are outside Git at:

`C:\Users\yzjsh\AppData\Local\Temp\paddlex-rank-replay-20260903-v2`

## ROI

The supplied 389x341 reference image showed the old outer frame at attachment
pixels x=27..120, y=206..296 and the inner numeric red frame at x=42..78,
y=242..271. Mapping that inner frame through the known 1920x1080 outer badge
rectangle `(0,885,144,140)` produces the production ROI:

- normalized: left=0.01198, top=0.87130, width=0.02969, height=0.04352
- 1920x1080 image: x=23, y=941, width=57, height=47
- right/bottom exclusive: x=80, y=988

The Python replay reports the equivalent exclusive rectangle `[23,941,80,988]`.
The application red annotation uses the inclusive-width form `(23,941,57,47)`.

## Replay Results

All six samples were run with provider `PADDLEX`, CPU device, PaddleX 3.7.2,
and PaddlePaddle from `C:\Users\yzjsh\.codex\paddlex-ocr-venv`. Numeric rank
is the only decision field; tier classification is intentionally `UNKNOWN` in
this numeric-only replay.

| sample | expected/visible | raw OCR | numeric rank | confidence | decision |
|---|---:|---|---:|---:|---|
| paddlex-ke-evil-20260831 | 8 | 8 | 8 | 0.9999674559 | RANK_RESOLVED |
| legacy-10-proxy-20260902 | 10 | 10 | 10 | 0.9999213219 | RANK_RESOLVED |
| legacy-9-20260902 | 9 | 9 | 9 | 0.9999845028 | RANK_RESOLVED |
| paddlex-shang8-20260903 | 8 | 8 | 8 | 0.9999743700 | RANK_RESOLVED |
| empty-20260830 | UNKNOWN | empty | UNKNOWN | UNKNOWN | UNKNOWN_FAIL_CLOSED |
| empty-20260901 | UNKNOWN | empty | UNKNOWN | UNKNOWN | UNKNOWN_FAIL_CLOSED |

The legacy `10` row is explicitly a proxy: no rank-detection success image
exists in that historical run, so the same-run mulligan frame was replayed.
The old PaddleX `Ke...` and `商8` failures are now reduced to the numeric ROI;
the latter is normalized to `8`, while unrelated/empty text remains fail-closed.

## Evidence Contract

Application rank evidence saves the original screenshot with a red ROI and a
diagnostic panel containing time, stage, run id, provider, ROI, raw/normalized
OCR, numeric/resolved rank, confidence, tier, unknown reason, and final
decision. Successful evidence is logged at INFO; unresolved evidence is WARN.
The panel is placed in a corner that avoids the marked ROI where possible.

## Timing Analysis and Experimental Gate

Historical application evidence showed the old rank check firing repeatedly
after the match click, while `warPhase=DRAWN_INIT_CARD` was still a transition
frame. The first capture at `00:06:16` was blank; later captures at
`00:06:31`, `00:06:42`, `00:06:52`, `00:07:03`, and `00:07:17` returned
`商8`. The match click was at `00:06:00`, GAMEPLAY appeared at `00:06:01`,
and the phase report was at `00:06:03`; the run was then F2-paused. This is
evidence of an early/unstable capture window, not evidence of a HUB false
positive.

| observed phase | rank OCR trigger | reason |
|---|---:|---|
| startup/desktop | no | no active war and `FILL_DECK` |
| HUB/matchmaking | no | no verified interactive game phase |
| `DRAWN_INIT_CARD` transition | no | badge can be blank or stale; historical run retried here |
| interactive mulligan `REPLACE_CARD` | yes | stable pre-mulligan rank decision point |
| normal `GAME_TURN` | no | rank policy is already outside its decision window |

The experimental gate is therefore `inWar && phase == REPLACE_CARD`. Rank
evidence records `provider`, `trigger=rank-policy-<phase>`, and `phase` in
both the OCR and screenshot evidence logs. The gate is fail-closed: if the
interactive phase is never observed, rank OCR does not invent a surrender
decision and the bounded existing retry/continue policy remains in charge.

## Screenshot Timing Audit

This audit uses existing screenshots and the UI/log text visible in those
screenshots. It does not start Hearthstone or claim a new E2E run. `UNKNOWN`
means that the exact screenshot is not authoritatively bound to an OCR event;
it is deliberately not filled from a nearby image. The current production ROI
is `[23,941,80,988]` (exclusive coordinates), unless the row says it was not
invoked.

| sample | absolute screenshot path | run/session | time/phase | should rank OCR trigger? | actual trigger point | ROI/bbox | provider | raw OCR | numeric result | confidence | final judgment/reason |
|---|---|---|---|---|---|---|---|---|---:|---:|---|
| login block | `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script\log\e2e-analysis-34832_6336\login-block-20260831-210453-844.png` | `e2e-analysis-34832_6336` | 2026-08-31 21:04:53; Battle.net login | No | No rank event visible; app log shows `E2E_PROCESS_CHECK` and `E2E_WINDOW_DISCOVERY` | not invoked; expected `[23,941,80,988]` | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | Correctly skipped: no active game phase and login is not a rank decision point. |
| HUB/matchmaking | `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script\log\e2e-analysis-43673_1458\live-20260831-203049-647.png` | `e2e-analysis-43673_1458` | 2026-08-31 20:30:49; HUB, searching for opponent | No | No `RANK_OCR` event visible; adjacent app log says current mode `HUB` and `开始匹配` | not invoked; expected `[23,941,80,988]` | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | Correctly skipped: matchmaking/loading screen has no verified interactive mulligan phase. |
| mulligan before selection | `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script\log\mulligan\game-0037-before-selection-20260902-162118-101.png` | `game-0037` | 2026-09-02 16:21:18; `REPLACE_CARD`, before selection | Yes | UI log visibly records `等级识别・OCR=10・白银10级` before this frame; exact capture binding is UNKNOWN | replay/current `[23,941,80,988]`; original trigger bbox UNKNOWN | UNKNOWN in original screenshot; PADDLEX only in offline replay | `10` in visible UI log | 10 | UNKNOWN original; replay 0.9999213219 | Rank was visibly readable and the phase was appropriate, but the original provider is not proven by this image. The replay result is not same-run runtime evidence. |
| mulligan in progress A | `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script\log\mulligan\game-0037-after-selection-0-20260902-162119-431.png` | `game-0037` | 2026-09-02 16:21:19; `REPLACE_CARD`, cards marked for replacement | Yes | No OCR event is bound to this exact screenshot | expected `[23,941,80,988]`; actual UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | Timing is eligible, but this frame is not independent OCR evidence. |
| mulligan in progress B | `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script\log\mulligan\game-0037-after-selection-1-20260902-162120-992.png` | `game-0037` | 2026-09-02 16:21:20; `REPLACE_CARD`, selection still in progress | Yes | No OCR event is bound to this exact screenshot | expected `[23,941,80,988]`; actual UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | Same conclusion: eligible timing, but no authoritative event attached to this frame. |
| post-confirm | `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script\log\mulligan\game-0037-post-confirm-20260902-162126-943.png` | `game-0037` | 2026-09-02 16:21:26; post-confirm, leaving mulligan | No | Visible log says `换牌选择已完成`; no rank event is visible | not invoked; expected `[23,941,80,988]` | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | Correctly outside the gate after confirmation; rank should not be re-read here. |
| normal gameplay A | `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script\log\e2e-analysis-43673_1458\pre-retry-20260831-203721-995.png` | `e2e-analysis-43673_1458` | 2026-08-31 20:37:21; normal gameplay, 5/5 mana | No | No rank event is bound to this frame | not invoked; expected `[23,941,80,988]` | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | Correctly skipped: normal gameplay does not need a rank decision. |
| normal gameplay B | `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script\log\e2e-analysis-34832_6336\our-turn-20260831-204738-035.png` | `e2e-analysis-34832_6336` | 2026-08-31 20:47:38; normal player turn | No | No rank event is bound to this frame | not invoked; expected `[23,941,80,988]` | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | Correctly skipped: current turn state is outside `REPLACE_CARD`. |
| historical early-error frame | `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script\log\unknown-states\rank-detection\2026-09-03\unknown-state-20260903-000717-283-rank-ocr-unresolved-763aa4fa-7284-444d-9b83-655e961bf2d9.png` | historical rank-OCR run; exact run id UNKNOWN | 2026-09-03 00:07:17; historical `DRAWN_INIT_CARD` transition | No under current gate | Old behavior did trigger rank OCR during the early transition; the surrounding historical log shows repeated checks at 00:06:16 through 00:07:17 | `[23,941,80,988]` | original runtime UNKNOWN; offline replay PADDLEX | offline replay `商8` | 8 | offline replay 0.9999743700; original UNKNOWN | This is the known early-screenshot error case. The first early frame was blank and later frames were contaminated/stale-looking `商8`; experimental gating skips this entire transition and only permits `REPLACE_CARD`. |

### Early Error Identification

The historical early-error samples are the repeated rank captures during
`DRAWN_INIT_CARD`, after matching but before the stable mulligan phase. They
were not HUB false positives: the app had already reached a game transition,
but the badge was not yet reliable. The old path could log an empty OCR result
at `00:06:16` and then retry into `商8` at later timestamps. The experimental
gate makes those captures impossible unless the state machine has explicitly
reported `REPLACE_CARD`; the skipped condition is recorded rather than
silently treated as a successful rank read.

The audit therefore separates three claims: screenshot timing is now gated by
phase, the six-image offline replay can validate PaddleX parsing, and original
runtime provider binding must come from the application event log. Only the
first two are established by this offline artifact; the rows marked UNKNOWN
remain open evidence gaps rather than passing runtime claims.
