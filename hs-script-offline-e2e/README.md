# Offline rank-surrender lifecycle replay

`hs-script-offline-e2e` is a standalone headless Maven module. It depends on `hs-script-recovery-core`, the same production rank/queue/surrender state logic used by the app, and does not depend on the GUI app, Hearthstone process, input device, or OCR provider. It replays the regression boundary without launching Hearthstone or sending input:

1. The prior game's Power.log must contain a real `CREATE_GAME`/mulligan start and terminal `CONCEDED`, `LOST`, opponent `WON`, and `FINAL_GAMEOVER` markers.
2. A fresh, confidently classified deck-selection screenshot completes only the old mandatory-surrender guard. A rank-4 badge shown on that menu is not read as a pre-match eligibility decision; the queue path remains post-mulligan.
3. The next Power.log game-start/mulligan event enters the current rank policy. Exact 5 and 10 may continue; rank 4, unknown, or failed OCR stays on the mandatory-surrender path.
4. A surrender request is not acceptance. The fixture must contain the authoritative terminal Power.log transition before cleanup capability is issued. A fresh current deck-selection screen is then required to release the next-queue lock.

Run only this module and its headless production-core dependency with:

```powershell
./mvnw.cmd -pl hs-script-offline-e2e -am test
```

The module's test fixtures, screenshots, replay, and capture utility live under this directory. The application module retains its normal integration tests; this standalone command is the quick offline acceptance replay.

The module also contains a cropped badge-only copy of the supplied historical Mulligan screenshot. Its metadata records the actual rank as **3**, phase `REPLACE_CARD`, and joins it to the original `hs_script.log` OCR and eligibility-denial excerpts (lines 19281/19285/19287). The crop is exactly 106×111 pixels from the lower-left badge, so the player name and full screen are not included. The historical log decision was `DENY reason=rank-evidence-stale`; it is not relabeled as the rank-4 regression or as a successful surrender.

## Refreshing a fixture from a new regression

Use `./capture-fixture.ps1` with explicit paths to the relevant Power.log, `hs_script.log`, and fresh deck-selection screenshot. The tool creates a new, non-overwriting, run-named directory, copies only lifecycle/rank/surrender lines, anonymizes Power.log entity names and the user-profile path, and copies the selected screenshots. It never deletes or changes source logs/screenshots. If adding an in-game rank screenshot, provide `-RankScreenshot` explicitly.

Review the output before checking it in. Screenshots can show account names or other identifying details; crop or redact those pixels first. Keep the action-request log separate from the authoritative Power.log terminal markers so a click/request cannot be mistaken for an accepted surrender. Add new numeric-rank, missing/OCR-failure, modal, delayed, or duplicate-event cases to the fixture JSON and extend the replay assertions; never update an expected result merely to make a failing case green.
