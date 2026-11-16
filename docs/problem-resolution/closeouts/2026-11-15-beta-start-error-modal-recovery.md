# Beta startup error modal recovery closeout

Issue: 2026-11-15 · Beta start-game error modal was classified as deck selection
Status: OPEN — source-only repair and release-path regression checks are complete; Beta deployment and paused-session online verification remain secretary-owned.

## Cause

The active `UpstreamScreenStateRecovery` path did not probe the centered
start-game error modal ROI or give its exact OCR contract precedence over the
deck-selection title underneath it. The same-frame OCR therefore produced
`DECK_SELECTION` with confidence 100, while the unknown mode gate blocked the
start path. When F2 paused the worker, recovery also correctly stopped, leaving
the modal visible; no manual input was authorized.

## Known-good comparison

The repair was compared with the exact legacy modal probe restored by
`53b90ff4` and the active upstream recovery behavior introduced by
`00a521ab` (also present in RC at `0754b198`). The concrete delta was that the
legacy path had the exact modal ROI/classifier contract, while upstream omitted
that ROI and modal precedence.

## Source fix

- Probe the centered modal ROI on fresh startup captures and classify the exact
  Chinese error dialog before deck selection.
- Keep unknown centered modals unresolved rather than authorizing the deck
  underlay.
- Require a fresh foreground/process/window-verified capture and safe runtime
  state before dispatching Confirm.
- Allow one accepted Confirm per continuous modal incident through
  `StartupErrorDialogDismissalGate`; require a fresh non-modal reprobe before
  re-arming.
- Make `MatchmakingDialogRecoveryPolicy` wait instead of dispatching a second
  Confirm while the modal remains visible.

## Verification

Passed:

- Focused recovery suite: 41 tests, 0 failures, 0 errors.
- Full Maven reactor build with the recovery-focused selector: BUILD SUCCESS.
- The release-path selector now includes both modal regressions, and the
  `BuildAndDeployVersion.Tests.ps1` selector-contract check passes.
- `MatchmakingSearchRecoveryTest`: 22; `ScreenStateRoiSelectorTest`: 3;
  `MatchmakingDialogRecoveryPolicyTest`: 16.
- `git diff --check` and the release-selector validator pass.

No Beta deployment, process restart, unpause, manual click, or live gameplay
was performed. The 12 pre-existing POM/version-stamp edits were preserved.

## Prevention contract

`build-and-deploy.ps1` now selects both `MatchmakingSearchRecoveryTest` and
`MatchmakingDialogRecoveryPolicyTest`, and `BuildAndDeployVersion.Tests.ps1`
asserts that both remain in the release-focused selector. The screenshot
regression must continue to prove that the exact modal outranks the deck
underlay, that an unknown modal produces no startup authorization, that only
one Confirm is accepted before a fresh transition, and that the fresh
post-dismissal deck frame is classifiable. The policy regression must continue
to prove wait/no-second-click behavior while the modal persists.
