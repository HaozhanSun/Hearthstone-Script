# Offline E2E replay suite

This directory is a deterministic, offline contract for the supervised
Hearthstone flow. It does not launch Hearthstone, JavaFX, OCR, PaddleX, native
input, or the external watchdog. The suite replays fixture metadata and log
fragments through a small test-only state machine.

Routing entry point:

- Manifest: `manifest.json`
- Replay test: `club.xiaojiawei.hsscript.e2ereplay.OfflineE2EReplaySuiteTest`
- Test-only engine: `club.xiaojiawei.hsscript.e2ereplay.OfflineE2EReplayEngine`
- Screenshot metadata: `screenshots/*.json`
- OCR/provider observations: `ocr/*.json`
- Power.log fragments: `powerlog/*.log`

The fixture is based on retained local evidence from the v4.16.160 run
`77249_6688`, including the 09:27 mulligan screenshots, the 09:31 terminal
screen, the old/fresh Power.log paths, and the observed `CREATE_GAME`,
`BEGIN_MULLIGAN`, `MAIN_ACTION`, and `PLAYSTATE` records. The screenshot
metadata deliberately records when an image was not retained; an absent image
must never be interpreted as visual proof.

The replay contract covers:

1. startup, login/HUB, mode entry, and matchmaking;
2. fresh Power.log attach and old-to-new timestamped path switching;
3. authoritative `CREATE_GAME` and `E2E_GAME_BOUNDARY` lineage;
4. mulligan and the `mulligan` milestone;
5. gameplay, foreground ownership, and F2 pause/F1 resume;
6. terminal wait and authoritative `PLAYSTATE` handling.

Every stage declares its screenshot manifest/path, OCR/provider expectation,
Power.log tokens, state-machine input/output, allowed actions, forbidden
actions, and failure reasons. Negative tests intentionally prove that stale
paths, missing boundaries, unconfirmed foreground, paused input, and
non-authoritative terminal pages fail closed.

