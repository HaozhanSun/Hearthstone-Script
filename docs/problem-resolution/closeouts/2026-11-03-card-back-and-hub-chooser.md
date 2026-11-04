# Problem-resolution investigation record

Issue: 2026-11-03 · Card-back reward and HUB chooser recovery regressions
Date: 2026-11-03
Status: OPEN — offline repair complete; Beta deployment and online evidence remain secretary-owned.

Investigation and cause: PASS — Beta v4.16.746 capture `debug-20261101-235531-276-screen-recovery-6a498d58-ce27-4e28-aade-06a4d7792c2f.png` correlated with `Power.log` session `Hearthstone_2026_11_01_23_44_57` showed a card-back reward during `FILL_DECK`, misrouted through broad Event Quest geometry and later UNKNOWN. The chooser log separates two issues: at 2026-11-01 23:45:33.655, `hubModeChooserVisual=true` and `matchmakingSearchVisual=true` while `startupQuestOverlayVisual=false`; repeated `STARTUP_MODE_ENTRY_DISPATCH_BLOCKED reason=chooser-proof-missing-or-stale` continued until a new capture at 23:46:32.999 had chooser=true and matchmaking=false, then `STARTUP_MODE_CHOOSER_CONFIRMED` at 23:46:51.907 and input accepted at 23:46:55.742. That roughly 80-second delay was the same-frame queue-color false positive withholding fresh one-use chooser proof until the next recovery probe. Separately, comparison with known-working `1511a55b90937a04689a4d4eeec9a1e113388c72` found a raw quest veto added to the HUB chooser condition; it is a source-level regression, but it was not active on this captured chooser frame.

Fix: PASS — Added a dedicated card-back structural classifier before Event Quest, preserving identity when Continue is temporarily absent and letting the terminal gate block input until a fresh Continue band exists. Restored known-working HUB chooser arbitration (`hubModeChooserVisual && !modalVisible`). Monthly five-chest screens now notify and manually pause before every award/chest/reward recovery action.

Verification: PARTIAL — Focused Beta-channel reactor at 2026-11-04 12:40–12:45 PST passed 83/0/0/0: `CardBackRewardOverlayRecoveryTest` 3/0/0, `SeasonRankAwardRecoveryDispatchTest` 19/0/0, `EventQuestOverlayRecoveryPolicyTest` 22/0/0, `MatchmakingSearchRecoveryTest` 21/0/0, `StuckScreenUnblockCallsiteIntegrationTest` 9/0/0, and `TerminalResultDismissalFixtureTest` 9/0/0. No deployed Beta or online evidence was collected.

Future prevention: PASS — Keep overlay identity separate from action affordance, compare recovery regressions against the last known-good commit first, and require recognized monthly reward stages to short-circuit all automated action/restart branches.

Prevention enforcement: PASS — test: `CardBackRewardOverlayRecoveryTest`; `SeasonRankAwardRecoveryDispatchTest`; `EventQuestOverlayRecoveryPolicyTest`; `MatchmakingSearchRecoveryTest`; and `StuckScreenUnblockCallsiteIntegrationTest` assert direct classifier/arbitration results and rejection paths.
