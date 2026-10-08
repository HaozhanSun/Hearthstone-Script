# Overnight Beta gotchas

## Matchmaking stage: deck-selection rank must never block queue input

**Observed failure (2026-10-08 PDT, Beta v4.16.603):** at 10:20:16 the
deck-selection badge read `传奇3级`, then the app logged
`MATCHMAKING_BLOCKED reason=pre-match-rank-not-5-or-10 action=NO_QUEUE_INPUT`.
That prevented a match from starting, so there was no current-game identity or
Mulligan phase in which the mandatory rank-surrender transaction could run.
The later `PAUSE_REQUESTED source=F2/low-level-hook` at 10:20:32 was a manual
F2 pause and is separate evidence, not the cause of the queue block.

**Guardrail:** matchmaking uses only runtime/pause and pending-surrender
guards. It must not OCR or evaluate the deck-selection rank badge. After
`CREATE_GAME` and the local `MULLIGAN_STATE=INPUT`, the active-game Mulligan
rank preflight reads fresh OCR: exactly rank 5 or 10 releases ordinary actions;
every other numeric value triggers the mandatory surrender path, including the
Settings and affirmative confirmation-dialog recovery steps. Unknown, failed,
or cancelled OCR does not block queue, but keeps ordinary in-game actions
behind the Mulligan rank barrier until a fresh retry authorizes or resolves the
game safely.

**Regression rule:** offline tests must prove rank 3 can dispatch the queue
callback and subsequently arms mandatory surrender only after active-game
evidence; ranks 5 and 10 dispatch and continue; unknown/PaddleX failure/cancel
dispatch the queue but cannot release ordinary actions; and a verified
surrender confirmation maps only to the affirmative action. A
`MATCHMAKING_BLOCKED` record whose reason begins `pre-match-rank-` is a
regression.

## Active-game rank surrender: first safe recovery inspection must not inherit the generic stuck timeout

**Observed failure (2026-10-08 PDT, deployed Beta v4.16.604):** queue input
was correctly allowed at 11:13:08 and the game reached Mulligan at 11:13:46.
Fresh PaddleX OCR resolved rank `3` at 11:14:05; the rank barrier correctly
denied ordinary play and logged `SURRENDER_ACTION_REQUESTED` at 11:14:06.
However, the mandatory-surrender worker repeatedly logged
`RANK_SURRENDER_RECOVERY_WAIT reason=below-threshold` and did not capture the
current Mulligan screen until 11:19:52. It then correctly clicked Settings,
Surrender, and the confirmation dialog, with terminal proof at 11:20:01. The
five-minute delay violated the one-minute no-progress limit even though the
eventual target and confirmation path were correct.

**Root cause:** the mandatory recovery branch returned whenever the shared
screen watchdog had not reached its generic retry/stuck threshold. Because an
inspection is what increments the mandatory attempt count, the first recovery
pass could never reach the retry threshold; a large configured stuck timeout
therefore suppressed the first safe Mulligan capture. This is distinct from a
post-click verification cooldown.

**Guardrail:** immediately after a fresh active-game rank denial, bypass only
the watchdog's initial threshold/cooldown to take one authoritative current
game capture. It may dispatch only the screen-specific next action (Mulligan
or gameplay -> Settings; Settings -> Surrender; confirmation -> affirmative
button); it must still require fresh visual/Power.log evidence and preserve all
later cooldowns and manual/ordinary-surrender behavior. Offline coverage must
prove the rank-denial path reaches the verified `CLICK_SETTINGS` action within
the configured short scheduler delay rather than waiting for the generic
stuck timeout.

## Empty current-session `Power.log` can deadlock startup screen recovery

**Observed facts (2026-10-06 PDT, Beta v4.16.586):**

- `hs_script.log` line 16563 records deployment ID
  `hs-script_v4.16.586-local-20261006-210857PDT.jar|7ee143ca1390df30`; line
  16565 records build time `2026-10-06 21:31:54 PDT`. Script PID was `88848`;
  Hearthstone PID was `97212`.
- Lines 16692-16710 repeatedly defer `GameStarter` startup probing because the
  verified game's current-session `Power.log` is empty (length 0).
- Line 16739 rejects a screen-recovery capture as
  `current-game-session-not-ready`. Line 16741 records failure at
  `NONE|NONE|FILL_DECK|NONE|0`. A second attempt is rejected at line 16756;
  line 16762 automatically pauses after the unresolved-attempt limit.
- There is no persisted screenshot correlated to the 21:53:30-21:56:30 events.
  A Home/quest-overlay description exists separately, but cannot be treated as
  pixel evidence for those log events.
- Source history contains prior Home progression before `Power.log` at
  `213bc256`; `2bb8168` later made non-empty current-session `Power.log` a
  prerequisite for ordinary screen captures.

**Root-cause hypothesis supported by source comparison:** the empty-log gate is
appropriate for gameplay/rank/queue actions, but is overly broad when applied to
read-only startup-menu observation. With the app still in `NONE` mode and
`FILL_DECK`, startup probing cannot classify the visible menu; generic recovery
then counts capture rejection as an unresolved stuck state and pauses. The logs
prove this path and pause sequence, but do not prove the visible pixels were the
Home quest overlay.

**Guardrail:** a pre-log observation may only be used when the current
Hearthstone HWND/PID, foreground, and sampled pixel ownership all match. It is
read-only and menu-classification-only; it must not authorize matchmaking,
gameplay, rank, or surrender input. Those paths continue to require a non-empty
Power.log bound to the current Hearthstone session. Unknown screens should end
in bounded no-input handling, not relaunch or repeated capture spam.
