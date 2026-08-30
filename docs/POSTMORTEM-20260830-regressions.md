# Regression postmortem — 2026-08-30

This is the read-only evidence record produced before code changes. It covers
the two reported regressions in the deployed v4.16.100-local runtime. The
PaddleX watchdog and the live Hearthstone client were not modified or started
from this worktree.

## Safe stop and process evidence

- Evidence capture time: 2026-08-30 12:30:30 PDT.
- The process query found no Hearthstone Copilot `java`/`javaw`, `wscript`,
  `cscript`, Maven, or `build-and-deploy.ps1` process belonging to the target
  runtime, so there was no automation process to terminate. The query output
  was saved at `%TEMP%\hearthstone-regression-stop-after.txt`.
- Hearthstone was deliberately retained: PID 165148, parent PID 165468,
  `D:\Hearthstone\Hearthstone Beta Launcher.exe -uid hs_beta`, created
  2026-08-30 01:53:06 PDT. No Hearthstone/config/data/log/plugin/backup path
  was deleted or changed.
- The app's diagnostic logical PID was 160864; it is not an active OS process
  in the stop-after snapshot. The associated app log still ends with
  `pause=true working=true` at line 3178.

## Evidence and run identifiers

Primary app log:

`C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script\log\hs_script.log`

The relevant run is identified by logical PID `160864` and the selected
Power.log run directory `D:\Hearthstone\Logs\Hearthstone_2026_08_30_12_04_52`.
The current Power.log is:

`D:\Hearthstone\Logs\Hearthstone_2026_08_30_12_04_52\Power.log`

The nearest prior Power.log runs inspected were
`D:\Hearthstone\Logs\Hearthstone_2026_08_30_11_46_37\Power.log`,
`D:\Hearthstone\Logs\Hearthstone_2026_08_30_10_10_44\Power.log`, and
`D:\Hearthstone\Logs\Hearthstone_2026_08_30_08_42_22\Power.log`.

## Regression 1 — F2 logged but actions continued

### State transition

In `hs_script.log`, lines 3097, 3113, 3133, 3139–3141, 3147, 3149–3150
contain repeated `捕捉到热键[F2]，暂停脚本` messages. The first authoritative
state line after the burst is line 3131:

`LIFECYCLE_STATE pid=160864 pause=true working=true ... mode=GAMEPLAY inWar=true`

The pause bit therefore did change, but `working` remained true. Lines
3091–3095, 3103–3108, 3119–3124, 3126–3130, 3134–3138, 3142–3148, and
3152–3156 show `E2E_INPUT_SENDINPUT_SENT ... accepted=true` around the F2
events; line 3157 records `手动暂停`. At line 3178 the app still reports
`pause=true working=true ... inWar=true`.

### Root cause

The known-working low-level F1/F2 hook path in the source comparison ref
`ea52cd6` was retained, but the deployed branch's `GlobalHotkeyListener.kt`
lines 221–226 only assigned `PauseStatus.isPause`. `PauseStatus.kt` lines
16–22 exposed a JavaFX property directly to hook/worker threads. More
importantly, `MouseUtil.kt` lines 704–714 and 853–859 validated window,
work-time, mode, and mouse configuration but not pause state. The normal
click path then rechecked only `working` at line 749 and sent input at lines
760–763. The E2E worker path also lacked a pause check immediately before its
move/press sequence. `TaskManager` could interrupt registered tasks, but it
did not provide a process-wide dispatch gate for already queued or retrying
workers.

This is why the log was truthful about the pause bit but false as an action
boundary: the state was paused while the input dispatcher still considered
the work session active.

### Source comparison

The regression is localized to the post-fixed-hotkey action-dispatch changes
at refs `0af9daa8` and `77ec7419`: F2 was made reliable at the hook layer,
while normal `MouseUtil` and queued E2E worker paths were not made atomic with
that state. The upstream baseline `e5d26c8` uses the older toggle path and is
not a sufficient safety implementation for the deployed fixed F1/F2 design.

## Regression 2 — surrender and winning-streak protection

### Protection condition and actual decision

`hs_script.log` lines 1–18 (and repeated later entries) contain:

`PERSISTENT_STREAK_GUARD_RECOVERY_CONTINUE ... consecutive-wins=13 threshold=5 ... action=CONTINUE surrenderPolicyPass=SKIPPED ... source=statistics.db`

The strategy is
`e71234fa-7-pirate-demon-hunter-mcts-global-plan-2f0f-4d4d-a5cf`; the
evidence list includes completed records 1606–1615. The win-rate diagnostic at
line 1523 reports `games=160 wins=68 rate=42.50%`, below its 45% threshold,
which is a separate condition and does not negate the winning-streak guard.
At line 3076 the later snapshot is `games=161 wins=68 rate=42.24%`.

Therefore the winning-streak protection condition was satisfied, but the
implementation intentionally converted it into CONTINUE and skipped the
remaining surrender policy. This is a guard execution failure, not an
unsatisfied condition.

### Rank, OCR, and terminal evidence

- Line 1520 records an empty rank OCR result (`tier=UNKNOWN rank=UNKNOWN`).
- Lines 1583, 1586–1588 show a later resolved `rank=9` and
  `SURRENDER_POLICY_TRIGGERED ... rule=current-rank-is-not-10`, followed by
  `立即投降` and `触发投降`.
- Lines 1591, 1706, 1805, 1932, 2025, 2134, and 2221 show seven surrender
  attempts while `mode=GAMEPLAY inWar=true`.
- Lines 2417 and 2944–3015 show PaddleX was selected, then failed with
  `OCR_PROVIDER_FAILED provider=PADDLEX fallback=false`; subsequent rank
  observations have empty OCR (`chars=0`, `rank=UNKNOWN`, `tier=UNKNOWN`).
  The failure was logged but the policy still had a path to continue after
  bounded unresolved attempts, without an explicit blocked decision.
- `D:\Hearthstone\Logs\Hearthstone_2026_08_30_12_04_52\Power.log` lines
  7992–7993 contain authoritative `Tyranny#31159 PLAYSTATE=WON` and
  `laz#12793 PLAYSTATE=LOST`; lines 8094–8095 repeat that terminal pair, and
  line 8120 reaches `STATE COMPLETE`. This terminal result must outrank any
  pending surrender click/retry.

### Root causes

1. `SurrenderPolicy.kt` lines 240–263 implemented
   `enforcePersistentStreakGuard()` as a recovery-only CONTINUE path. The
   stated protection was never dispatched as a surrender, so five or more
   non-surrendered wins could start another game.
2. `GameUtil.kt` lines 604–705 checked pause, mode, war count, and a bounded
   retry count, but did not have a shared terminal-result gate immediately
   before each surrender action. A stale/late surrender request could
   coexist with authoritative `WON`/`LOST`/settlement state.
3. `CurrentRankDetector.kt` returned nullable detection after PaddleX/empty
   OCR. `SurrenderPolicy.kt` lines 400–413 could log `RANK_POLICY_CONTINUE`
   after unresolved attempts. This did not prove the safe rank; OCR failure
   must be an explicit blocked/unknown state, not implicit success or safety.

The evidence does not establish that the rank rule itself was wrong: rank 9
would request surrender under the configured current-rank policy. It does
establish that the winning-streak guard was met but bypassed, and that the
terminal and OCR failure states were not hard boundaries.

## Required post-fix invariants

- F2 atomically makes pause visible to all workers; every state-changing
  action, queue, retry, surrender, and replan checks the gate immediately
  before dispatch. Existing queued work is rejected with an explicit
  `ACTION_BLOCKED ... reason=paused` line. F1 is an explicit resume and emits
  `RESUME_ACTIVE`.
- A matched persistent streak guard returns an actual surrender decision only
  when its threshold is met; ordinary games retain the prior policy behavior.
- An authoritative WON/LOST/CONCEDED/settlement state wins over surrender.
- PaddleX OCR failure remains observable and cannot be treated as resolved rank
  or safe success; the decision is explicitly blocked until new evidence or a
  terminal state arrives.
- No real game is started from this worker before offline tests and a new
  release artifact have passed verification.
