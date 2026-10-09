# Offline rank-surrender lifecycle replay

`hs-script-offline-e2e` is a standalone headless Maven module. It depends on `hs-script-recovery-core`, the same production rank/queue/surrender state logic used by the app, and does not depend on the GUI app, Hearthstone process, input device, or OCR provider. It replays the regression boundary without launching Hearthstone or sending input:

1. The prior game's Power.log must contain a real `CREATE_GAME`/mulligan start and terminal `CONCEDED`, `LOST`, opponent `WON`, and `FINAL_GAMEOVER` markers.
2. The first matchmaking input requires a single-use pre-match permit based on a fresh deck-selection rank read: only exact rank 5 or 10, PaddleX confidence at least 0.95, no older than five seconds, in Tournament deck selection, and no active game. Rank 4/6/9/11, Legendary numeric values, unknown/missing OCR, provider failure/cancellation, low confidence, stale evidence, or mode/phase mismatch must produce **zero matchmaking dispatches**.
3. Only after an eligible pre-match permit starts a game may the Power.log game-start/mulligan event enter the independent current-game rank preflight. That in-game check remains defense in depth: exact 5 and 10 may continue; every other numeric rank takes the mandatory-surrender path. Unknown/failed in-game OCR holds ordinary gameplay input pending bounded fresh resolution; it never creates a queue bypass.
4. A surrender request is not acceptance. The fixture must contain the authoritative terminal Power.log transition before cleanup capability is issued. A fresh current deck-selection screen releases the previous game's cleanup lock, but it does not itself authorize the next queue; the next attempt must acquire a new pre-match rank permit.

Run only this module and its headless production-core dependency with:

```powershell
./mvnw.cmd -pl hs-script-offline-e2e -am test
```

The module's test fixtures, screenshots, replay, and capture utility live under this directory. The application module retains its normal integration tests; this standalone command is the quick offline acceptance replay.

The module includes the source-bound rank-4 and rank-3 script-log decision excerpts from the Oct. 3 rotated Beta logs, plus a clearly-labelled synthetic pre-match rank-4 denial trace bound to the retained deck-selection screenshot fixture. The source excerpts do not contain a gameId; the referenced Power.log files were not available to independently establish one. The rank-3 evidence records `selectedRank=3` and `DENY reason=rank-evidence-stale`, and its log-generated rank ROI screenshot filename is preserved. The 106×111 supplied badge crop remains a separate visual fixture and is not claimed to be byte-derived from that logged ROI. The app policy suite additionally covers OCR provider timeout/failure/cancellation normalization, confidence, age, bounds, mode mismatch, and no-dispatch assertions.

The rank-4 script excerpt proves only that the app logged a rank-4 denial and surrender request; it is not linked to the sanitized synthetic Power.log gameId. The offline lifecycle replay separately uses its checked-in Power.log snapshots: the startup snapshot proves a game/mulligan began, and the terminal snapshot is the only evidence that accepts surrender. Script request logs or policy capabilities never stand in for authoritative Power.log acceptance. `capture-fixture.ps1` was not suitable for these decision-only excerpts because it requires a readable Power.log and a deck-selection screenshot for a new lifecycle bundle.

## Refreshing a fixture from a new regression

Use `./capture-fixture.ps1` with explicit paths to the relevant Power.log, `hs_script.log`, and fresh deck-selection screenshot. The tool creates a new, non-overwriting, run-named directory, copies only lifecycle/rank/surrender lines, anonymizes Power.log entity names and the user-profile path, and copies the selected screenshots. It never deletes or changes source logs/screenshots. If adding an in-game rank screenshot, provide `-RankScreenshot` explicitly.

Review the output before checking it in. Screenshots can show account names or other identifying details; crop or redact those pixels first. Keep the action-request log separate from the authoritative Power.log terminal markers so a click/request cannot be mistaken for an accepted surrender. Add new numeric-rank, missing/OCR-failure, modal, delayed, or duplicate-event cases to the fixture JSON and extend the replay assertions; every unsafe pre-match case must assert zero queue input, while only fresh exact 5/10 fixtures may assert a single first dispatch. Never update an expected result merely to make a failing case green.
